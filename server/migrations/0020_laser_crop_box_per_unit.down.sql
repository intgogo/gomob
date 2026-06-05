-- 回滚到单框：丢弃 b 单元框，恢复 bay_key 单列主键，删 unit 列。
BEGIN;

DELETE FROM laser_crop_box WHERE unit <> 'a';
ALTER TABLE laser_crop_box DROP CONSTRAINT laser_crop_box_pkey;
ALTER TABLE laser_crop_box ADD PRIMARY KEY (bay_key);
ALTER TABLE laser_crop_box DROP COLUMN unit;

COMMIT;
