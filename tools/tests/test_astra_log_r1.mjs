// SPDX-License-Identifier: Apache-2.0
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
import test from 'node:test';
import { BLACK, CUT, PRIMARIES, colorTransform, decode, encode, frameConverter, hlgInverse } from '../astra_log_r1_color.mjs';
import { rawFrames, sourceProfile } from '../astra_hlg_to_log.mjs';

const near = (a, b, tolerance = 1e-9) => assert.ok(Math.abs(a - b) < tolerance, a + ' vs ' + b);

test('signed Astra transfer is monotonic, invertible and has the OCLog3 positive anchors', () => {
    near(encode(0), 0.095);
    near(encode(0.18), 0.4);
    near(encode(16), 0.92);
    const samples = [-10, -1, -0.25, -0.01, -0.001, 0, 0.001, CUT, 0.18, 1, 3.7396, 16, 100];
    for (const [i, x] of samples.entries()) {
        near(decode(encode(x)), x);
        if (i) assert.ok(encode(x) > encode(samples[i - 1]));
    }
    for (const x of [0, CUT]) {
        const eps = 1e-7;
        near((encode(x) - encode(x - eps)) / eps, (encode(x + eps) - encode(x)) / eps, 0.0001);
    }
});

test('legacy MATCH_SDR plateaus while normalized direct HLG holds distinct highlights', () => {
    const gain = 0.18 / hlgInverse(0.38);
    const old = signal => 0.1 + 0.8 * Math.log1p(50 * Math.min(1, gain * hlgInverse(signal))) / Math.log(51);
    near(old(0.8), old(1));
    near(encode(gain * hlgInverse(0.38)), 0.4);
    near(encode(gain * hlgInverse(1)), 0.74921809, 1e-8);
    assert.ok(encode(gain * hlgInverse(1)) > encode(gain * hlgInverse(0.8)));
    assert.notEqual(Math.round(1023 * encode(gain * hlgInverse(0.990))), Math.round(1023 * encode(gain * hlgInverse(0.999))));
});

test('matrix, primaries and range are separate and neutral codes stay neutral', () => {
    const out = new Float64Array(3);
    const draw = colorTransform('bt2020nc', 'tv', PRIMARIES.bt2020, 0.38);
    draw(64 + 0.38 * 876, 512, 512, out, 0, 1);
    out.forEach(x => near(x, 0.4));
    draw(63, 512, 512, out, 0, 1);
    out.forEach(x => assert.ok(x < BLACK));
    draw(960, 512, 512, out, 0, 1);
    out.forEach(x => assert.ok(decode(x) > decode(0.74921809)));
    draw(400, 560, 512, out, 0, 1);
    assert.ok(out[1] > out[2] && out[2] > out[0]);
    const full = colorTransform('bt709', 'pc', PRIMARIES.bt709, 0.38);
    full(0.38 * 1023, 512, 512, out, 0, 1);
    out.forEach(x => near(x, 0.4));
});

test('planar 10-bit render respects chroma phase and keeps float precision', () => {
    const raster = new Uint16Array(12).fill(400);
    raster.set([480, 544, 512, 512], 8);
    const draw = colorTransform('bt2020nc', 'tv', PRIMARIES.bt2020, 0.38);
    const left = frameConverter(4, 2, 'left', draw)(new Uint8Array(raster.buffer));
    const center = frameConverter(4, 2, 'center', draw)(new Uint8Array(raster.buffer));
    assert.equal(left.length, 24);
    assert.ok(left[8] < left[9] && left[9] < left[10]);
    assert.notEqual(left[9], center[9]);
    assert.ok(Math.abs(left[9] * 255 - Math.round(left[9] * 255)) > 0.01);
});

test('stream profile rejects unknown metadata and records explicit interpretations', () => {
    const s = { pix_fmt: 'yuv420p10le', color_transfer: 'arib-std-b67', color_space: 'bt2020nc', color_range: 'tv', color_primaries: 'bt2020' };
    assert.throws(() => sourceProfile(s, {}), /chroma siting/);
    assert.equal(sourceProfile(s, { chroma: 'left', range: 'pc' }).range, 'pc');
    assert.throws(() => sourceProfile({ ...s, pix_fmt: 'yuv420p' }, {}), /10-bit/);
});

test('split frames reconstruct, and a partial final frame fails', async () => {
    const values = [];
    for await (const frame of rawFrames([Buffer.from([1]), Buffer.from([2, 3, 4])], 2)) values.push([...frame]);
    assert.deepEqual(values, [[1, 2], [3, 4]]);
    await assert.rejects(async () => {
        for await (const frame of rawFrames([Buffer.from([1])], 2)) assert.fail(frame);
    }, /incomplete/);
});

test('Resolve inverse DCTL agrees with the published analytical constants', async () => {
    const text = await readFile(new URL('../../docs/color/astra-log-r1/astra-log-r1-inverse.dctl', import.meta.url), 'utf8');
    const fn = text.slice(text.indexOf('{') + 1, text.indexOf('}'))
        .replaceAll('const float', 'const')
        .replaceAll('_expf', 'Math.exp').replace(/(?<=[0-9])f\b/g, '');
    const dctl = new Function('code', fn);
    for (const x of [-1, -0.25, -0.001, 0, 0.004, 0.008, 0.18, 1, 16, 100]) near(dctl(encode(x)), x);
});
