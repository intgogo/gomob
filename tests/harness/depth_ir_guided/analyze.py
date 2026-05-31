#!/usr/bin/env python3
# 判定 IR 引导深度精修是否值得接入:读 prototype.py 的 results.json，
# 按边缘质量 + 留一法补洞 RMS 给出 正常 / 警告 / 异常 + 原因。
#
# 判定逻辑(数据驱动,非拍脑袋):
#   - "IR 引导有益" 需同时:IR 边缘 F1 不显著低于深度边缘 F1，且 IR 留一法 RMS 不高于深度。
#   - 否则结论 = IR 引导(朴素边缘法)无益,维持 depth-only 精修。
# 用法:analyze.py <out_dir>
import json
import sys
from pathlib import Path

# 判定阈值
F1_PARITY = 0.85       # IR 边缘 F1 至少要达到深度边缘的 85% 才算"可作边界源"
RMS_TOL = 1.05         # IR 留一法 RMS 不超过深度的 1.05× 才算"不更差"


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else '.dev/depth_ir_guided')
    s = json.loads((out / 'results.json').read_text())['summary']

    ir_f1 = s['ir_edge_f1']; de_f1 = s['depth_edge_f1']
    ir_rms = s['loo_ir_guided_rms']; de_rms = s['loo_depth_only_rms']
    ir_near = s['loo_ir_guided_near']; de_near = s['loo_depth_only_near']
    desp = s.get('ir_despeckle_f1', {})

    print('=== IR 引导深度精修判定 ===')
    print(f'配对帧: {s["n_pairs"]}  真边界像素: {s["true_edge_px"]}')
    print(f'边缘对真边界 F1 : IR={ir_f1:.3f}  深度自身={de_f1:.3f}  (IR/深度={ir_f1/de_f1:.2f})')
    print(f'  去散斑 IR F1  : ' + '  '.join(f'{k}={v:.3f}' for k, v in desp.items()))
    print(f'留一法补洞 RMS : IR引导={ir_rms:.1f}mm  深度={de_rms:.1f}mm  (近边界 IR={ir_near:.1f} 深度={de_near:.1f})')

    edge_ok = ir_f1 >= F1_PARITY * de_f1
    rms_ok = ir_rms <= RMS_TOL * de_rms
    best_desp = max(desp.values()) if desp else ir_f1

    print()
    if edge_ok and rms_ok:
        print('✅ 正常:IR 边缘质量与补洞精度均不差于深度 → IR 引导精修值得接入管线。')
        rc = 0
    else:
        reasons = []
        if not edge_ok:
            reasons.append(f'IR 边缘 F1({ir_f1:.2f})远低于深度({de_f1:.2f}) —— 0x0500 是结构光散斑帧,'
                           f'Canny 检到散斑非物体边界;去散斑最佳 F1 仅 {best_desp:.2f}(recall 崩),救不回。')
        if not rms_ok:
            reasons.append(f'IR 引导留一法 RMS({ir_rms:.0f}mm)远高于深度({de_rms:.0f}mm) —— '
                           f'IR 散斑无法按深度面分区,平面拟合跨面 → 填值发散。')
        print('⛔ 结论:朴素 IR 边缘引导无益,维持 depth-only 精修(真置信+时域+空间降噪)。')
        for r in reasons:
            print('   · ' + r)
        print('   · 提示:IR 的真实潜在价值不在"边缘",而可能在"置信/有效性"'
              '(无 IR 回波=无结构光信号、强光饱和像素=深度不可信),属另一实验。')
        rc = 1
    return rc


if __name__ == '__main__':
    sys.exit(main())
