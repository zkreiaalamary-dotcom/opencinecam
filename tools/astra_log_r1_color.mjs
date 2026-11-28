// SPDX-License-Identifier: Apache-2.0

// OCLog3 v1 positive transfer with a signed, C1 extension. Float interchange only.
export const ASTRA_ID = 'opencinecam.astra-log-r1.float.v1';
export const BLACK = 0.095;
export const TOE_SLOPE = 5.570237810615516;
export const CUT = 0.008;
export const CUT_CODE = BLACK + CUT * TOE_SLOPE;
export const NEGATIVE_SCALE = 0.02;
const C = 0.013131196250227292;
const A = 0.11770578833655286;
const D = 0.5935536972214088;

export function encode(linear) {
    if (linear < 0) return BLACK - NEGATIVE_SCALE * Math.log1p(-linear * TOE_SLOPE / NEGATIVE_SCALE);
    if (linear < CUT) return BLACK + linear * TOE_SLOPE;
    return A * Math.log(linear + C) + D;
}

export function decode(code) {
    if (code < BLACK) return -(NEGATIVE_SCALE / TOE_SLOPE) * Math.expm1((BLACK - code) / NEGATIVE_SCALE);
    if (code < CUT_CODE) return (code - BLACK) / TOE_SLOPE;
    return Math.exp((code - D) / A) - C;
}

const HLG_A = 0.17883277;
const HLG_B = 1 - 4 * HLG_A;
const HLG_C = 0.5 - HLG_A * Math.log(4 * HLG_A);

export function hlgInverse(code) {
    const e = Math.abs(code);
    const light = e <= 0.5 ? e * e / 3 : (Math.exp((e - HLG_C) / HLG_A) + HLG_B) / 12;
    return Math.sign(code) * light;
}

export const MATRICES = {
    bt2020nc: [0.2627, 0.0593],
    bt709: [0.2126, 0.0722],
};

export const PRIMARIES = {
    bt2020: [[1, 0, 0], [0, 1, 0], [0, 0, 1]],
    bt709: [
        [0.6274038959, 0.3292830384, 0.0433130657],
        [0.0690972894, 0.9195403951, 0.0113623155],
        [0.0163914389, 0.0880133079, 0.8955952532],
    ],
};

export const CHROMA_PHASE = {
    left: [0, 0.5], center: [0.5, 0.5], topleft: [0, 0], top: [0.5, 0],
    bottomleft: [0, 1], bottom: [0.5, 1],
};

export const RANGES = { tv: [64, 876, 896], pc: [0, 1023, 1023] };

/** Y', Cb and Cr are codes, including studio-range excursions. Output is BT.2020 Astra Log. */
export function colorTransform(matrix, range, to2020, greySignal) {
    const [kr, kb] = MATRICES[matrix];
    const [black, scaleY, scaleC] = RANGES[range];
    const kg = 1 - kr - kb;
    const blue = 2 * (1 - kb);
    const red = 2 * (1 - kr);
    const gain = 0.18 / hlgInverse(greySignal);
    return (y, cb, cr, out, pixel, planeSize) => {
        const luma = (y - black) / scaleY;
        const u = (cb - 512) / scaleC;
        const v = (cr - 512) / scaleC;
        const r = gain * hlgInverse(luma + red * v);
        const g = gain * hlgInverse(luma - blue * kb / kg * u - red * kr / kg * v);
        const b = gain * hlgInverse(luma + blue * u);
        const [mr, mg, mb] = to2020;
        out[pixel] = encode(mg[0] * r + mg[1] * g + mg[2] * b);
        out[pixel + planeSize] = encode(mb[0] * r + mb[1] * g + mb[2] * b);
        out[pixel + 2 * planeSize] = encode(mr[0] * r + mr[1] * g + mr[2] * b);
    };
}

function axis(size, phase) {
    const lower = new Int32Array(size);
    const upper = new Int32Array(size);
    const weight = new Float64Array(size);
    for (let i = 0; i < size; i++) {
        const p = Math.max(0, Math.min(size / 2 - 1, (i - phase) / 2));
        lower[i] = Math.floor(p);
        upper[i] = Math.min(size / 2 - 1, lower[i] + 1);
        weight[i] = p - lower[i];
    }
    return { lower, upper, weight };
}

/** Input is tight planar 4:2:0 LE with 10 bits in each word's low bits, as emitted by ffmpeg. */
export function frameConverter(width, height, chroma, transform) {
    if (width < 2 || height < 2 || width % 2 || height % 2) throw new Error('Expected an even 4:2:0 raster');
    const [phaseX, phaseY] = CHROMA_PHASE[chroma];
    const xAxis = axis(width, phaseX);
    const yAxis = axis(height, phaseY);
    const pixels = width * height;
    const chromaWidth = width / 2;
    const output = new Float32Array(pixels * 3);
    function chromaAt(input, offset, x, y) {
        const first = offset + yAxis.lower[y] * chromaWidth;
        const last = offset + yAxis.upper[y] * chromaWidth;
        const a = xAxis.lower[x];
        const b = xAxis.upper[x];
        const wx = xAxis.weight[x];
        const wy = yAxis.weight[y];
        const top = input[first + a] + (input[first + b] - input[first + a]) * wx;
        const bottom = input[last + a] + (input[last + b] - input[last + a]) * wx;
        return top + (bottom - top) * wy;
    }
    return frame => {
        if (frame.byteLength !== pixels * 3) throw new Error('Truncated planar video frame');
        const input = new Uint16Array(frame.buffer, frame.byteOffset, frame.byteLength / 2);
        for (let y = 0; y < height; y++) {
            for (let x = 0; x < width; x++) {
                const p = width * y + x;
                transform(input[p], chromaAt(input, pixels, x, y), chromaAt(input, pixels * 1.25, x, y), output, p, pixels);
            }
        }
        return output; // Reused for each frame. Callers must await transfer before conversion.
    };
}
