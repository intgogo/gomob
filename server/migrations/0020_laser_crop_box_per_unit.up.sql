-- M10.1 车位框按单元（镜头 A/B 各存一个独立裁剪/测量框）。
--
-- 原 0019 每装机点一行(bay_key 主键)= 单一世界系框；现扩为 (bay_key, unit) 复合主键：
--   unit='a' 框在 unit_a/融合世界系（== 原行语义，DEFAULT 'a' 平滑迁移现有数据，无需回填）；
--   unit='b' 框在 unit_b 设备系。
-- 各自持久化、各自裁剪预览；测量时各单元云按各自框去背景（unitB 经 B→A 并入世界系）后并集测量。
-- 为什么按镜头：每台相机看到的背景不同，在各自点云空间圈各自 ROI 比单一世界框更贴合"针对每个单独镜头"。

BEGIN;

ALTER TABLE laser_crop_box ADD COLUMN unit TEXT NOT NULL DEFAULT 'a';
ALTER TABLE laser_crop_box DROP CONSTRAINT laser_crop_box_pkey;
ALTER TABLE laser_crop_box ADD PRIMARY KEY (bay_key, unit);

COMMENT ON COLUMN laser_crop_box.unit IS '镜头单元 a|b：a 框在 unit_a/融合世界系，b 框在 unit_b 设备系';

COMMIT;
