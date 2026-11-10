"""Export FaceNet to ExecuTorch .pte for one backend.

  python export_pte.py --backend xnnpack|vulkan|qnn [--out DIR] [--soc SM8350] [--precision ...]

xnnpack: fp32, CPU (same graph and weights as upstream model.pte).
vulkan : fp32, GPU via VulkanPartitioner; ops Vulkan can't take fall back to XNNPACK.
qnn    : needs Linux x86_64 + Qualcomm QNN SDK (QNN_SDK_ROOT); see README.md.
"""
import argparse
import os

import torch
from executorch.exir import to_edge_transform_and_lower

import facenet_model


def partitioners(backend):
    from executorch.backends.xnnpack.partition.xnnpack_partitioner import XnnpackPartitioner

    if backend == "xnnpack":
        return [XnnpackPartitioner()]
    if backend == "vulkan":
        from executorch.backends.vulkan.partitioner.vulkan_partitioner import VulkanPartitioner

        return [VulkanPartitioner(), XnnpackPartitioner()]
    raise ValueError(backend)


def export_qnn(model, inputs, args):
    # Imported lazily: needs the QNN SDK and ExecuTorch's QNN python adaptors for this host.
    from executorch.backends.qualcomm.serialization.qc_schema import QcomChipset
    from executorch.backends.qualcomm.utils.utils import (
        generate_htp_compiler_spec,
        generate_qnn_executorch_compiler_spec,
        to_edge_transform_and_lower_to_qnn,
    )
    from executorch.backends.qualcomm.quantizer.quantizer import QnnQuantizer, QuantDtype
    from torchao.quantization.pt2e.quantize_pt2e import convert_pt2e, prepare_pt2e

    import calibration

    exported = torch.export.export(model, inputs, strict=True).module()
    use_fp16 = args.precision == "fp16"
    if not use_fp16:
        quantizer = QnnQuantizer()
        quantizer.set_default_quant_config(QuantDtype[args.precision])
        prepared = prepare_pt2e(exported, quantizer)
        for x in calibration.batches():
            prepared(*x)
        exported = convert_pt2e(prepared)
    backend_options = generate_htp_compiler_spec(use_fp16=use_fp16)
    compiler_specs = generate_qnn_executorch_compiler_spec(
        soc_model=getattr(QcomChipset, args.soc), backend_options=backend_options
    )
    edge = to_edge_transform_and_lower_to_qnn(exported, inputs, compiler_specs)
    return edge.to_executorch()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backend", required=True, choices=["xnnpack", "vulkan", "qnn"])
    ap.add_argument("--out", default=os.path.join(facenet_model.HERE, "out"))
    ap.add_argument("--soc", default="SM8350")
    ap.add_argument("--precision", default="use_8a8w", help="qnn only: fp16 | use_8a8w | use_16a8w")
    args = ap.parse_args()

    model = facenet_model.build()
    inputs = facenet_model.example_input()
    os.makedirs(args.out, exist_ok=True)

    if args.backend == "qnn":
        program = export_qnn(model, inputs, args)
        name = f"facenet-qnn-{args.soc.lower()}-{args.precision.replace('use_', '')}.pte"
    else:
        exported = torch.export.export(model, inputs, strict=True)
        program = to_edge_transform_and_lower(exported, partitioner=partitioners(args.backend)).to_executorch()
        name = f"facenet-{args.backend}-fp32.pte"
    path = os.path.join(args.out, name)
    with open(path, "wb") as f:
        program.write_to_file(f)
    print(f"{path} {os.path.getsize(path)} bytes sha256={facenet_model.sha256(path)}")


if __name__ == "__main__":
    main()
