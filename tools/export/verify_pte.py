"""Compare embeddings of a .pte against the PyTorch reference (and optionally another .pte).

  python verify_pte.py out/facenet-xnnpack-fp32.pte [--against ../../app/src/main/assets/model.pte]

Runs on the host ExecuTorch runtime, so only .pte files whose delegates exist on the host can run
(XNNPACK yes; Vulkan/QNN no). Inputs: tools/export/.cache/imgs/*.jpg (if any) + seeded random images.
"""
import argparse
import glob
import os

import numpy as np
import torch
from PIL import Image
from executorch.runtime import Runtime

import facenet_model


def images():
    out = []
    for f in sorted(glob.glob(os.path.join(facenet_model.CACHE, "imgs", "*.jpg"))):
        img = Image.open(f).convert("RGB").resize((160, 160), Image.BILINEAR)
        out.append((os.path.basename(f), np.asarray(img, dtype=np.float32)))
    rng = np.random.default_rng(0)
    for i in range(3):
        out.append((f"random{i}", rng.uniform(0, 255, (160, 160, 3)).astype(np.float32)))
    return out


def run_pte(path):
    method = Runtime.get().load_program(path).load_method("forward")
    return lambda x: method.execute([torch.from_numpy(x)[None]])[0].numpy()[0]


def cos(a, b):
    return float(a @ b / np.linalg.norm(a) / np.linalg.norm(b))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("pte")
    ap.add_argument("--against")
    ap.add_argument("--min-cos", type=float, default=0.9999)
    args = ap.parse_args()
    model = facenet_model.build()
    ref = lambda x: model(torch.from_numpy(x)[None]).detach().numpy()[0]
    cand = run_pte(args.pte)
    other = run_pte(args.against) if args.against else None
    worst = 1.0
    with torch.no_grad():
        for name, x in images():
            c, r = cand(x), ref(x)
            line = f"{name:22s} cos(ref)={cos(c, r):.6f} max|d|={np.abs(c - r).max():.2e}"
            worst = min(worst, cos(c, r))
            if other:
                o = other(x)
                line += f" cos(against)={cos(c, o):.6f}"
                worst = min(worst, cos(c, o))
            print(line)
    print(f"worst cosine {worst:.6f} (threshold {args.min_cos})")
    raise SystemExit(0 if worst >= args.min_cos else 1)


if __name__ == "__main__":
    main()
