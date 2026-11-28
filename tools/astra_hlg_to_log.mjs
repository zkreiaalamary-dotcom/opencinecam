// SPDX-License-Identifier: Apache-2.0
import { execFile, spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdir, readFile, realpath, writeFile } from 'node:fs/promises';
import { endianness } from 'node:os';
import { join, resolve } from 'node:path';
import { promisify, parseArgs } from 'node:util';
import { pathToFileURL } from 'node:url';
import {
    ASTRA_ID, CHROMA_PHASE, MATRICES, PRIMARIES, RANGES, colorTransform, frameConverter,
} from './astra_log_r1_color.mjs';

export function sourceProfile(stream, options, calibration) {
    if (stream.pix_fmt !== 'yuv420p10le' || stream.color_transfer !== 'arib-std-b67') {
        throw new Error('Expected 10-bit planar 4:2:0 video tagged HLG (arib-std-b67)');
    }
    if (stream.field_order && !['progressive', 'unknown'].includes(stream.field_order)) {
        throw new Error('Interlaced sources are outside the Astra R1 contract');
    }
    const matrix = options.matrix ?? stream.color_space;
    const primaries = options.primaries ?? stream.color_primaries;
    const range = options.range ?? stream.color_range;
    const chroma = options.chroma ?? stream.chroma_location;
    if (!Object.hasOwn(MATRICES, matrix) || !Object.hasOwn(RANGES, range) || !Object.hasOwn(CHROMA_PHASE, chroma)) {
        throw new Error('Matrix, range and chroma siting must be known. Use --matrix, --range and --chroma to supply measured values.');
    }
    const to2020 = calibration?.to2020 ?? PRIMARIES[primaries];
    if ((!calibration && !Object.hasOwn(PRIMARIES, primaries)) ||
        !Array.isArray(to2020) || to2020.length !== 3 ||
        !to2020.every(row => Array.isArray(row) && row.length === 3 &&
            row.every(value => Number.isFinite(value) && Math.abs(value) <= 16))) {
        throw new Error('Specify supported --primaries or a calibration JSON with finite 3x3 to2020 rows');
    }
    if (calibration && options.primaries) throw new Error('Choose --primaries or --calibration');
    const grey = Number(options.grey ?? calibration?.greyHlg ?? 0.38);
    if (!(grey >= 0.01 && grey <= 0.95)) throw new Error('Reference HLG grey must be in [0.01, 0.95]');
    return { matrix, primaries: calibration ? 'custom' : primaries, range, chroma, to2020, grey };
}

export async function* rawFrames(chunks, frameSize) {
    const frame = Buffer.alloc(frameSize);
    let filled = 0;
    for await (const chunk of chunks) {
        let offset = 0;
        while (offset < chunk.length) {
            const count = Math.min(chunk.length - offset, frameSize - filled);
            chunk.copy(frame, filled, offset, offset + count);
            filled += count;
            offset += count;
            if (filled === frameSize) {
                yield frame;
                filled = 0;
            }
        }
    }
    if (filled) throw new Error('Decoder produced an incomplete frame');
}

function waitForChild(child, label) {
    const finished = once(child, 'close').then(([code, signal]) => {
        if (code !== 0) throw new Error(label + ' failed (' + (signal ?? code) + ')');
    });
    // Observe errors even if the peer process is still transferring frames.
    finished.catch(() => {});
    return finished;
}

export async function convert(input, output, options) {
    const inputPath = await realpath(input);
    const outputPath = resolve(output);
    const ffmpeg = options.ffmpeg ?? 'ffmpeg';
    const ffprobe = options.ffprobe ?? 'ffprobe';
    const { stdout } = await promisify(execFile)(ffprobe, [
        '-v', 'error', '-select_streams', 'v:0', '-show_streams', '-show_frames',
        '-show_entries', 'stream=index,width,height,pix_fmt,color_transfer,color_space,color_range,color_primaries,chroma_location,time_base,avg_frame_rate,field_order,sample_aspect_ratio:stream_side_data=rotation:frame=best_effort_timestamp_time,pkt_duration_time,duration_time',
        '-of', 'json', inputPath,
    ], { maxBuffer: 64 * 1024 * 1024 });
    const { streams, frames } = JSON.parse(stdout);
    if (streams?.length !== 1 || !frames?.length || frames.some(f => !Number.isFinite(Number(f.best_effort_timestamp_time)))) {
        throw new Error('Video stream or per-frame timestamps unavailable');
    }

    const stream = streams[0];
    const calibration = options.calibration ? JSON.parse(await readFile(options.calibration, 'utf8')) : undefined;
    const profile = sourceProfile(stream, options, calibration);
    const { width, height } = stream;
    const transform = colorTransform(profile.matrix, profile.range, profile.to2020, profile.grey);
    const renderFrame = frameConverter(width, height, profile.chroma, transform);
    await mkdir(outputPath); // Refuse existing directories, including links or alternate spellings.
    const decoder = spawn(ffmpeg, [
        '-nostdin', '-v', 'error', '-noautorotate', '-i', inputPath, '-map', '0:v:0',
        '-fps_mode', 'passthrough', '-pix_fmt', 'yuv420p10le', '-f', 'rawvideo', 'pipe:1',
    ], { stdio: ['ignore', 'pipe', 'inherit'] });
    const decoderFinished = waitForChild(decoder, 'FFmpeg decode');

    const writer = spawn(ffmpeg, [
        '-v', 'error', '-n', '-f', 'rawvideo', '-pix_fmt', 'gbrpf32le',
        '-video_size', width + 'x' + height, '-i', 'pipe:0',
        '-c:v', 'exr', '-compression', 'zip1', '-format', 'float', '-start_number', '1',
        join(outputPath, 'astra_%08d.exr'),
    ], { stdio: ['pipe', 'ignore', 'inherit'] });
    writer.stdin.on('error', () => {}); // The write callback reports the same failure to the transfer.
    const writerFinished = waitForChild(writer, 'FFmpeg EXR encode');
    let count = 0;
    try {
        for await (const frame of rawFrames(decoder.stdout, width * height * 3)) {
            if (++count > frames.length) throw new Error('Decoder returned more frames than ffprobe');
            const data = renderFrame(frame);
            const bytes = Buffer.from(data.buffer, data.byteOffset, data.byteLength);
            await new Promise((done, fail) => writer.stdin.write(bytes, error => error ? fail(error) : done()));
        }
        if (count !== frames.length) throw new Error('Decoder frame count differs from ffprobe');
        writer.stdin.end();
        await Promise.all([decoderFinished, writerFinished]);
        await writeFile(join(outputPath, 'astra-source.json'), JSON.stringify({
            transfer: ASTRA_ID, gamut: 'BT.2020-D65', representation: 'OpenEXR float32, LOG codes',
            source: inputPath, stream, profile, options: {
                matrix: options.matrix, range: options.range, primaries: options.primaries,
                chroma: options.chroma, grey: options.grey, calibration: options.calibration,
            },
            frameNumberOrigin: 1,
            sourceFrameTimestamps: frames,
        }, null, 2) + '\n', { flag: 'wx' });
        return count;
    } catch (error) {
        decoder.kill();
        writer.kill();
        throw error;
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
    try {
        if (endianness() !== 'LE') throw new Error('Little-endian host required');
        const { positionals, values } = parseArgs({
            allowPositionals: true,
            options: Object.fromEntries(['matrix', 'range', 'primaries', 'chroma', 'grey', 'calibration', 'ffmpeg', 'ffprobe']
                .map(name => [name, { type: 'string' }])),
        });
        if (positionals.length !== 2) throw new Error('Usage: node tools/astra_hlg_to_log.mjs input.mp4 new-output-directory [--matrix bt2020nc] [--range tv] [--primaries bt2020] [--chroma left]');
        const count = await convert(...positionals, values);
        console.log('Exported ' + count + ' frames and astra-source.json');
    } catch (error) {
        console.error(error.message);
        process.exitCode = 1;
    }
}
