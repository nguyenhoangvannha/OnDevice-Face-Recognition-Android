"""Estimate how much 8-bit quantisation (what a Hexagon V68 NPU needs) shifts FaceNet embeddings.

Host-only simulation with PT2E + XNNPACKQuantizer (per-channel int8 weights, int8 activations), as a
proxy for QNN 8a8w; the real QNN/HTP numbers will differ somewhat. Data: LFW pairs test split
(HF dataset logasja/lfw, pairs/test), faces centre-cropped. Usage: python quant_drift.py [--pairs 600]
"""
import argparse

import numpy as np
import torch
from torchao.quantization.pt2e.quantize_pt2e import convert_pt2e, prepare_pt2e
from executorch.backends.xnnpack.quantizer.xnnpack_quantizer import (
    XNNPACKQuantizer,
    get_symmetric_quantization_config,
)

import facenet_model
from calibration import rows as lfw_rows, to_input


def best_accuracy(sims, labels):
    best = (0.0, 0.0)
    for t in np.linspace(0, 1, 201):
        acc = float(np.mean((sims >= t) == labels))
        best = max(best, (acc, t))
    return best


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pairs", type=int, default=600)
    ap.add_argument("--calib", type=int, default=128)
    args = ap.parse_args()
    rows = lfw_rows()
    rng = np.random.default_rng(0)
    order = rng.permutation(len(rows))
    calib_rows = [rows[i] for i in order[: args.calib // 2]]
    eval_rows = [rows[i] for i in order[args.calib // 2 : args.calib // 2 + args.pairs]]

    model = facenet_model.build()
    example = facenet_model.example_input()
    quantizer = XNNPACKQuantizer().set_global(get_symmetric_quantization_config(is_per_channel=True))
    prepared = prepare_pt2e(torch.export.export(model, example, strict=True).module(), quantizer)
    with torch.no_grad():
        for r in calib_rows:
            prepared(to_input(r["img_0"]["bytes"]))
            prepared(to_input(r["img_1"]["bytes"]))
    q = convert_pt2e(prepared)

    def emb(m, x):
        e = m(x)[0].detach().numpy()
        return e / np.linalg.norm(e)

    drift, labels, s_fp, s_q, s_cross = [], [], [], [], []
    with torch.no_grad():
        for r in eval_rows:
            a, b = to_input(r["img_0"]["bytes"]), to_input(r["img_1"]["bytes"])
            fa, fb, qa, qb = emb(model, a), emb(model, b), emb(q, a), emb(q, b)
            drift += [float(fa @ qa), float(fb @ qb)]
            labels.append(r["pair"] == 1)
            s_fp.append(float(fa @ fb))
            s_q.append(float(qa @ qb))
            s_cross.append(float(fa @ qb))  # enrolled on CPU fp32, queried on int8
    labels = np.array(labels)
    drift = np.array(drift)
    print(f"pairs={len(eval_rows)} calib_images={2 * len(calib_rows)}")
    print(f"cos(fp32, int8) per image: mean={drift.mean():.4f} p5={np.percentile(drift, 5):.4f} min={drift.min():.4f}")
    for name, s in (("fp32 vs fp32", s_fp), ("int8 vs int8", s_q), ("fp32 enrolled vs int8 query", s_cross)):
        s = np.array(s)
        acc, t = best_accuracy(s, labels)
        print(f"{name:28s} same={s[labels].mean():.3f} diff={s[~labels].mean():.3f} best_acc={acc:.3f} @thr={t:.2f}")


if __name__ == "__main__":
    main()
