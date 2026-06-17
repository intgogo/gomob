// lidar_cli — headless M1 entry point. Subcommands grow as the pipeline lands.
// Today: config inspection + PCD info + legacy CALIB3D reproduction (offline-validated).
#include <cstdio>
#include <cstring>
#include <string>

#include <filesystem>
#include <fstream>
#include <thread>
#include <vector>

#include "cloud/cloud_build.h"
#include "cloud/fusion.h"
#include "cloud/io_pcd.h"
#include "cloud/legacy_calib3d.h"
#include "cloud/registration.h"
#include "calib/framing_stream.h"
#include "calib/site_marker_calib.h"
#include "config/calibration_json.h"
#include "config/config_yaml.h"
#include "device/http_client.h"
#include "device/scan_stream.h"
#include "device/stream_capture.h"
#include "pipeline/pipeline.h"
#include "pipeline/scan_vehicle.h"

// Parse all frames of a raw capture dump into typed LDR/PTS frames.
static void replayFrames(const std::string& path, std::vector<lidar::device::LdrFrame>& ldrs,
                         lidar::CloudXYZ& pts) {
  std::ifstream in(path, std::ios::binary);
  std::vector<std::uint8_t> buf((std::istreambuf_iterator<char>(in)), {});
  std::size_t off = 0;
  while (off + 12 <= buf.size()) {
    lidar::device::Frame fr; std::string err;
    std::size_t used = lidar::device::parseFrame(buf.data() + off, buf.size() - off, fr, err);
    if (used == 0) { if (!err.empty()) { ++off; continue; } break; }
    if (fr.type == lidar::device::MsgType::LDR) {
      lidar::device::LdrFrame l; if (lidar::device::decodeLDR(fr.payload, l)) ldrs.push_back(l);
    } else if (fr.type == lidar::device::MsgType::PTS) {
      lidar::device::PtsFrame p;
      if (lidar::device::decodePTS(fr.payload, p))
        for (const auto& q : p.points)
          if (q.x != 0.0 || q.y != 0.0 || q.z != 0.0)
            pts.points.emplace_back(float(q.x), float(q.y), float(q.z));
    }
    off += used;
  }
  pts.width = pts.points.size(); pts.height = 1;
}

// Extract every IMG (V) frame from a raw capture dump as a JPEG named by its heading.
// Returns the number of JPEGs written (also fills `headings` with each frame's h_angle_deg).
static std::size_t replayImg(const std::string& path, const std::string& out_dir,
                             std::vector<double>& headings) {
  namespace fs = std::filesystem;
  fs::create_directories(out_dir);
  std::ifstream in(path, std::ios::binary);
  std::vector<std::uint8_t> buf((std::istreambuf_iterator<char>(in)), {});
  std::size_t off = 0, n = 0;
  while (off + 12 <= buf.size()) {
    lidar::device::Frame fr; std::string err;
    std::size_t used = lidar::device::parseFrame(buf.data() + off, buf.size() - off, fr, err);
    if (used == 0) { if (!err.empty()) { ++off; continue; } break; }
    if (fr.type == lidar::device::MsgType::IMG) {
      lidar::device::ImgFrame im;
      if (lidar::device::decodeIMG(fr.payload, im) && !im.jpeg.empty()) {
        char name[64];
        std::snprintf(name, sizeof name, "img_%03zu_h%+08.2f.jpg", n, im.h_angle_deg);
        std::ofstream o((fs::path(out_dir) / name).string(), std::ios::binary);
        o.write(reinterpret_cast<const char*>(im.jpeg.data()), im.jpeg.size());
        headings.push_back(im.h_angle_deg);
        ++n;
      }
    }
    off += used;
  }
  return n;
}

// Load a per-unit world cloud from either a .pcd or a raw PTS capture (.bin -> decodePTS).
static lidar::CloudXYZ::Ptr loadCloudSource(const std::string& path) {
  if (path.size() > 4 && path.substr(path.size() - 4) == ".bin") {
    std::vector<lidar::device::LdrFrame> ldrs;
    auto pts = std::make_shared<lidar::CloudXYZ>();
    replayFrames(path, ldrs, *pts);
    return pts;
  }
  return lidar::loadPCD(path);
}

static int usage() {
  std::printf(
      "lidar_cli — headless LIDAR_PTZ pipeline (M1)\n"
      "usage:\n"
      "  lidar_cli config <config.yaml>            inspect a config\n"
      "  lidar_cli pcdinfo <cloud.pcd>             point count + bbox\n"
      "  lidar_cli legacy <setting.ini> <points3D.txt> [out.pcd]\n"
      "                                            CALIB3D transform+crop (mm)\n"
      "  lidar_cli downsample <in.pcd> <ratio> <out.pcd>   random keep-ratio\n"
      "  lidar_cli crop <in.pcd> <xmn> <ymn> <zmn> <xmx> <ymx> <zmx> <out.pcd>\n"
      "  lidar_cli fuse <out.pcd> <a.pcd> <b.pcd> [c.pcd...]   point-set union\n"
      "  lidar_cli register <target.pcd> <source.pcd> <out_fused.pcd> [voxel] [maxcorr]\n"
      "                                            ICP-align source->target, fuse (R5)\n"
      "  lidar_cli calib-site-markers <imgA> <cfgA> <calibA> <imgB> <cfgB> <calibB> <out_site.json> [len_m] [min_common]\n"
      "                                            现场共享 ArUco 标记场自标定 B->A (写 site_extrinsic)\n"
      "  lidar_cli site-extrinsic <targetA> <sourceB> <out.json> [voxel] [maxcorr]\n"
      "                                            freeze inter-unit transform B->A (ICP, once)\n"
      "  lidar_cli scan-vehicle <unitA> <unitB> <out_dir> [none|icp|site.json] [setting.ini|-] [keep]\n"
      "                                            reconstruct vehicle: align->union->crop->save (pipeline B)\n"
      "  lidar_cli scan-vehicle-live <ipA> <ipB> <out_dir> [none|icp|site.json] [keep]\n"
      "                                            PASSIVE live: capture both sweeps -> reconstruct (USER triggers)\n"
      "  lidar_cli pipeline <config.yaml> <cloud.pcd> <out_dir> [img heading_rad]...\n"
      "                                            offline replay: synth->texture->export\n"
      "  lidar_cli device info|status <ip> [port] live device_info/device_status\n"
      "  lidar_cli device calib <ip> [out.json] [port]  pull stored calibration -> JSON\n"
      "  lidar_cli device capture <ip> <port> <seconds> [raw.bin]  capture+decode scan stream\n"
      "  lidar_cli replay pts <raw.bin> <out.pcd>             device PTS xyz -> PCD\n"
      "  lidar_cli replay img <raw.bin> <out_dir>             extract IMG JPEGs (named by heading)\n"
      "  lidar_cli replay ldr <raw.bin> <config.yaml> <calib.json|-> <out.pcd>  LDR->lineToWorld->PCD\n");
  return 2;
}

int main(int argc, char** argv) {
  if (argc < 2) return usage();
  const std::string cmd = argv[1];

  try {
    if (cmd == "config" && argc >= 3) {
      auto c = lidar::loadConfig(argv[2]);
      std::printf("planes=%zu  device_t=[%.3f %.3f %.3f]  q_norm=%.6f\n",
                  c.lidar.planes.size(), c.device_pose.translation.x(),
                  c.device_pose.translation.y(), c.device_pose.translation.z(),
                  c.device_pose.quaternion.norm());
      std::printf("camera fx,fy,cx,cy = %.2f %.2f %.2f %.2f\n",
                  c.camera.init_intrinsics[0], c.camera.init_intrinsics[1],
                  c.camera.init_intrinsics[2], c.camera.init_intrinsics[3]);
      std::printf("enable_synthesis=%d enable_texture=%d synthesis_voxel=%.3f\n",
                  c.debug.enable_synthesis, c.debug.enable_texture_mapping, c.debug.synthesis_voxel);
      return 0;
    }
    if (cmd == "pcdinfo" && argc >= 3) {
      auto cloud = lidar::loadPCD(argv[2]);
      auto b = lidar::bbox(*cloud);
      std::printf("points=%zu  bbox x[%.1f,%.1f] y[%.1f,%.1f] z[%.1f,%.1f]\n",
                  cloud->size(), b.min.x(), b.max.x(), b.min.y(), b.max.y(), b.min.z(), b.max.z());
      return 0;
    }
    if (cmd == "legacy" && argc >= 4) {
      auto s = lidar::loadLegacySettings(argv[2]);
      auto dev = lidar::loadAsciiXYZ(argv[3]);
      auto world = lidar::transformAndCrop(*dev, s.calib, s.crop);
      auto b = lidar::bbox(*world);
      std::printf("in=%zu  cropped=%zu  bbox x[%.1f,%.1f] y[%.1f,%.1f] z[%.1f,%.1f]\n",
                  dev->size(), world->size(), b.min.x(), b.max.x(), b.min.y(), b.max.y(), b.min.z(), b.max.z());
      if (argc >= 5 && lidar::savePCDBinary(argv[4], *world)) std::printf("wrote %s\n", argv[4]);
      return 0;
    }
    if (cmd == "downsample" && argc >= 5) {
      auto in = lidar::loadPCD(argv[2]);
      auto out = lidar::randomKeep(*in, std::stod(argv[3]));
      lidar::savePCDBinary(argv[4], *out);
      std::printf("in=%zu ratio=%s kept=%zu -> %s\n", in->size(), argv[3], out->size(), argv[4]);
      return 0;
    }
    if (cmd == "crop" && argc >= 10) {
      auto in = lidar::loadPCD(argv[2]);
      Eigen::Vector3d mn(std::stod(argv[3]), std::stod(argv[4]), std::stod(argv[5]));
      Eigen::Vector3d mx(std::stod(argv[6]), std::stod(argv[7]), std::stod(argv[8]));
      auto out = lidar::cropBox(*in, mn, mx, true);
      lidar::savePCDBinary(argv[9], *out);
      std::printf("in=%zu cropped=%zu -> %s\n", in->size(), out->size(), argv[9]);
      return 0;
    }
    if (cmd == "fuse" && argc >= 5) {
      std::vector<lidar::CloudXYZ::ConstPtr> clouds;
      for (int i = 3; i < argc; ++i) clouds.push_back(lidar::loadPCD(argv[i]));
      auto out = lidar::fuse(clouds);
      lidar::savePCDBinary(argv[2], *out);
      std::printf("fused %d clouds -> %zu points -> %s\n", argc - 3, out->size(), argv[2]);
      return 0;
    }
    if (cmd == "pipeline" && argc >= 5) {
      lidar::PipelineInputs in;
      in.config_path = argv[2];
      in.cloud_pcd = argv[3];
      in.output_dir = argv[4];
      for (int i = 5; i + 1 < argc; i += 2) in.images.push_back({argv[i], std::stod(argv[i + 1])});
      auto o = lidar::runOfflinePipeline(in);
      std::printf("in=%zu out=%zu mapped=%zu textured=%d\n", o.input_points, o.output_points, o.mapped_points, o.textured);
      std::printf("  %s\n", o.synthesized_pcd.c_str());
      if (o.textured) { std::printf("  %s\n  %s\n", o.textured_pcd.c_str(), o.panorama_jpg.c_str()); }
      std::printf("  %s\n", o.calibration_json.c_str());
      return 0;
    }
    if (cmd == "register" && argc >= 5) {
      auto target = lidar::loadPCD(argv[2]);
      auto source = lidar::loadPCD(argv[3]);
      double voxel = (argc >= 6) ? std::stod(argv[5]) : 0.08;
      double maxc  = (argc >= 7) ? std::stod(argv[6]) : 1.0;
      auto r = lidar::registerTwoUnits(*source, *target, voxel, maxc);
      std::printf("ICP: converged=%d best_yaw=%d fitness=%.5f\n", r.converged, r.best_yaw_deg, r.fitness);
      if (!r.converged) { std::printf("  registration did not converge; fused naively\n"); }
      auto src_t = r.converged ? lidar::applyTransform(*source, r.transform) : source;
      std::vector<lidar::CloudXYZ::ConstPtr> cs{target, src_t};
      auto fused = lidar::fuse(cs);
      lidar::savePCDBinary(argv[4], *fused);
      auto b = lidar::bbox(*fused);
      std::printf("fused %zu pts -> %s  bbox x[%.2f,%.2f] y[%.2f,%.2f] z[%.2f,%.2f]\n",
                  fused->size(), argv[4], b.min.x(), b.max.x(), b.min.y(), b.max.y(), b.min.z(), b.max.z());
      return 0;
    }
    if (cmd == "calib-site-markers" && argc >= 9) {
      // calib-site-markers <imgA> <cfgA> <calibA> <imgB> <cfgB> <calibB> <out_site.json> [len_m] [min_common]
      lidar::SiteMarkerConfig cfg;
      if (argc >= 10) cfg.marker_len_m = std::stod(argv[9]);
      if (argc >= 11) cfg.min_common = std::stoi(argv[10]);
      auto r = lidar::calibrateSiteMarkers(argv[2], argv[3], argv[4], argv[5], argv[6], argv[7], cfg);
      std::printf("site-marker: ok=%d common=%d rms=%.4fm  %s\n", r.ok, r.n_common, r.rms_m, r.msg.c_str());
      if (!r.ok) { std::printf("  未达标，不保存\n"); return 1; }
      if (!lidar::saveSiteExtrinsic(argv[8], r.b_to_a)) { std::printf("error: 保存失败\n"); return 1; }
      std::printf("frozen site extrinsic (B->A, markers) -> %s\n", argv[8]);
      return 0;
    }
    if (cmd == "framing-stream" && argc >= 9) {
      // framing-stream <ipA> <cfgA> <calibA> <ipB> <cfgB> <calibB> <out_site.json> [len_m] [min_common] [prevW]
      // 被动连两单元 4003 取景流：stdout 写二进制帧协议（见 framing_stream.h），stderr 走日志。
      lidar::FramingStreamParams fp;
      fp.ipA = argv[2]; fp.configA = argv[3]; fp.calibA = argv[4];
      fp.ipB = argv[5]; fp.configB = argv[6]; fp.calibB = argv[7];
      fp.outJSON = argv[8];
      if (argc >= 10) fp.marker_len_m = std::stod(argv[9]);
      if (argc >= 11) fp.min_common = std::stoi(argv[10]);
      if (argc >= 12) fp.preview_width = std::stoi(argv[11]);
      return lidar::runFramingStream(fp, stdout);
    }
    if (cmd == "site-extrinsic" && argc >= 5) {  // site-extrinsic <targetA> <sourceB> <out.json> [voxel] [maxcorr]
      auto target = loadCloudSource(argv[2]);
      auto source = loadCloudSource(argv[3]);
      double voxel = (argc >= 6) ? std::stod(argv[5]) : 0.08;
      double maxc  = (argc >= 7) ? std::stod(argv[6]) : 1.0;
      auto r = lidar::registerTwoUnits(*source, *target, voxel, maxc);
      std::printf("ICP: converged=%d best_yaw=%d fitness=%.5f\n", r.converged, r.best_yaw_deg, r.fitness);
      if (!r.converged) { std::printf("  did not converge; not saving\n"); return 1; }
      if (!lidar::saveSiteExtrinsic(argv[4], r.transform)) { std::printf("error: save failed\n"); return 1; }
      std::printf("frozen site extrinsic (B->A) -> %s\n", argv[4]);
      return 0;
    }
    if (cmd == "scan-vehicle" && argc >= 5) {     // scan-vehicle <unitA> <unitB> <out_dir> [align] [setting.ini] [keep]
      lidar::ScanVehicleParams p;
      auto unitA = loadCloudSource(argv[2]);
      auto unitB = loadCloudSource(argv[3]);
      p.out_dir = argv[4];
      const std::string align = (argc >= 6) ? argv[5] : "none";   // none | icp | <site.json>
      if (align == "icp") p.use_icp = true;
      else if (align != "none") p.site_extrinsic = align;
      if (argc >= 7 && std::string(argv[6]) != "-") { p.crop = true; p.setting_ini = argv[6]; }
      if (argc >= 8) p.keep_ratio = std::stod(argv[7]);
      auto r = lidar::reconstructVehicle(*unitA, *unitB, p);
      const auto& b = r.vehicle_bbox;
      std::printf("scan-vehicle: A=%zu B=%zu -> fused=%zu (align=%s) -> keep=%zu -> crop=%zu\n",
                  r.pts_a, r.pts_b, r.fused, r.align_method.c_str(), r.after_downsample, r.after_crop);
      std::printf("  vehicle bbox x[%.2f,%.2f] y[%.2f,%.2f] z[%.2f,%.2f]\n",
                  b.min.x(), b.max.x(), b.min.y(), b.max.y(), b.min.z(), b.max.z());
      std::printf("  %s\n  %s\n  %s\n", r.vehicle_pcd.c_str(), r.points3d_txt.c_str(),
                  r.pointcloud_number_txt.c_str());
      return 0;
    }
    if (cmd == "scan-vehicle-live" && argc >= 5) {  // scan-vehicle-live <ipA> <ipB> <out_dir> [none|icp|site.json] [keep]
      const std::string ipA = argv[2], ipB = argv[3], outDir = argv[4];
      std::filesystem::create_directories(outDir);
      const std::string binA = outDir + "/scanA_pts.bin", binB = outDir + "/scanB_pts.bin";
      std::printf("PASSIVE live scan: polling .101/.102 status; please trigger BOTH scans now.\n");
      std::printf("  (no SCAN_START sent by us — capturing PTS 4010 until each sweep completes)\n");
      lidar::device::SweepCaptureResult ra, rb;
      std::thread ta([&]{ ra = lidar::device::captureSweep(ipA, 4010, 4000, 400, 8000, 180000, 0.0, binA); });
      std::thread tb([&]{ rb = lidar::device::captureSweep(ipB, 4010, 4000, 400, 8000, 180000, 0.0, binB); });
      ta.join(); tb.join();
      std::printf("  unitA(%s): connected=%d sweep=%d frames=%zu crc_ok=%zu final=%s\n",
                  ipA.c_str(), ra.connected, ra.sweep_seen, ra.frames, ra.crc_ok, ra.final_state.c_str());
      std::printf("  unitB(%s): connected=%d sweep=%d frames=%zu crc_ok=%zu final=%s\n",
                  ipB.c_str(), rb.connected, rb.sweep_seen, rb.frames, rb.crc_ok, rb.final_state.c_str());
      if (!ra.sweep_seen || !rb.sweep_seen) { std::printf("error: a sweep was not observed; aborting.\n"); return 1; }
      lidar::ScanVehicleParams p;
      auto unitA = loadCloudSource(binA);
      auto unitB = loadCloudSource(binB);
      p.out_dir = outDir;
      const std::string align = (argc >= 6) ? argv[5] : "icp";
      if (align == "icp") p.use_icp = true; else if (align != "none") p.site_extrinsic = align;
      if (argc >= 7) p.keep_ratio = std::stod(argv[6]);
      auto r = lidar::reconstructVehicle(*unitA, *unitB, p);
      const auto& b = r.vehicle_bbox;
      std::printf("scan-vehicle-live: A=%zu B=%zu -> fused=%zu (align=%s) -> keep=%zu -> crop=%zu\n",
                  r.pts_a, r.pts_b, r.fused, r.align_method.c_str(), r.after_downsample, r.after_crop);
      std::printf("  vehicle bbox x[%.2f,%.2f] y[%.2f,%.2f] z[%.2f,%.2f]\n",
                  b.min.x(), b.max.x(), b.min.y(), b.max.y(), b.min.z(), b.max.z());
      std::printf("  %s\n  %s\n", r.vehicle_pcd.c_str(), r.pointcloud_number_txt.c_str());
      return 0;
    }
    if (cmd == "replay" && argc >= 4) {
      const std::string sub = argv[2];
      if (sub == "pts" && argc >= 5) {           // replay pts <raw.bin> <out.pcd>
        std::vector<lidar::device::LdrFrame> ldrs; lidar::CloudXYZ pts;
        replayFrames(argv[3], ldrs, pts);
        lidar::savePCDBinary(argv[4], pts);
        auto b = lidar::bbox(pts);
        std::printf("PTS: %zu pts  bbox x[%.3f,%.3f] y[%.3f,%.3f] z[%.3f,%.3f] -> %s\n",
                    pts.size(), b.min.x(), b.max.x(), b.min.y(), b.max.y(), b.min.z(), b.max.z(), argv[4]);
        return 0;
      }
      if (sub == "img" && argc >= 5) {           // replay img <raw.bin> <out_dir>
        std::vector<double> headings;
        std::size_t k = replayImg(argv[3], argv[4], headings);
        std::printf("IMG: %zu jpegs -> %s/", k, argv[4]);
        if (!headings.empty())
          std::printf("  headings %.1f..%.1f deg", headings.front(), headings.back());
        std::printf("\n");
        return 0;
      }
      if (sub == "ldr" && argc >= 6) {           // replay ldr <raw.bin> <config.yaml> <calib.json|-> <out.pcd>
        std::vector<lidar::device::LdrFrame> ldrs; lidar::CloudXYZ dummy;
        replayFrames(argv[3], ldrs, dummy);
        auto cfg = lidar::loadConfig(argv[4]);
        auto sp = lidar::SynthesisParams::fromConfig(cfg);
        if (std::string(argv[5]) != "-") { auto cp = lidar::loadCalibrationJson(argv[5]); sp.applyCalibration(cp); }
        auto cloud = lidar::buildFromLDR(ldrs, sp);
        lidar::savePCDBinary(argv[6], *cloud);
        auto b = lidar::bbox(*cloud);
        double hmin = 0.0, hmax = 0.0;
        if (!ldrs.empty()) {
          hmin = hmax = ldrs.front().h_angle_deg;
          for (const auto& l : ldrs) {
            hmin = std::min(hmin, l.h_angle_deg);
            hmax = std::max(hmax, l.h_angle_deg);
          }
        }
        std::printf("LDR->lineToWorld: %zu lines h[%.3f,%.3f] span=%.3f, %zu pts  bbox x[%.3f,%.3f] y[%.3f,%.3f] z[%.3f,%.3f] -> %s\n",
                    ldrs.size(), hmin, hmax, hmax - hmin, cloud->size(),
                    b.min.x(), b.max.x(), b.min.y(), b.max.y(), b.min.z(), b.max.z(), argv[6]);
        return 0;
      }
      return usage();
    }
    if (cmd == "device" && argc >= 4) {
      const std::string sub = argv[2];
      const std::string ip = argv[3];
      std::string err;
      if (sub == "info") {
        int port = (argc >= 5) ? std::stoi(argv[4]) : 4000;
        lidar::device::DeviceClient cli(ip, port);
        lidar::device::DeviceInfo di;
        if (!cli.getDeviceInfo(di, err)) { std::printf("error: %s\n", err.c_str()); return 1; }
        std::printf("model=%s sn=%s hw=%s sw=%s net=%s\n", di.model.c_str(), di.sn.c_str(), di.hwver.c_str(), di.swver.c_str(), di.network.c_str());
        std::printf("lidar=%s %s:%d  camera=%s %dx%d fps=%.2f  encoder res=%d multi=%d  calib=%d\n",
                    di.lidar_model.c_str(), di.lidar_ip.c_str(), di.lidar_port, di.camera_model.c_str(),
                    di.camera_width, di.camera_height, di.camera_fps, di.encoder_resolution, di.encoder_multi, di.has_calib);
        return 0;
      }
      if (sub == "status") {
        int port = (argc >= 5) ? std::stoi(argv[4]) : 4000;
        lidar::device::DeviceClient cli(ip, port);
        lidar::device::DeviceStatus st;
        if (!cli.getDeviceStatus(st, err)) { std::printf("error: %s\n", err.c_str()); return 1; }
        std::printf("state=%s online=%d (enc=%d lidar=%d cam=%d ctl=%d) angle=%.4f zero=%.4f temp=%.1f err=0x%02lx uptime=%.0f msg='%s'\n",
                    st.state.c_str(), st.online(), st.encoder_online, st.lidar_online, st.camera_online, st.control_online,
                    st.latest_angle, st.zero_degs, st.tempre, st.error_code, st.uptime, st.scan_msg.c_str());
        return 0;
      }
      if (sub == "capture" && argc >= 6) {
        int port = std::stoi(argv[4]);
        int secs = std::stoi(argv[5]);
        std::string raw = (argc >= 7) ? argv[6] : "";
        auto s = lidar::device::captureStream(ip, port, secs, raw);
        if (!s.connected) { std::printf("error: %s\n", s.error.c_str()); return 1; }
        std::printf("port %d: bytes=%zu frames=%zu crc_ok=%zu crc_bad=%zu\n", port, s.bytes, s.frames, s.crc_ok, s.crc_bad);
        std::printf("  msg types:"); for (auto& [t, n] : s.type_counts) std::printf(" %c=%d", t, n); std::printf("\n");
        if (!s.sample_kind.empty()) {
          std::printf("  sample %s: %s\n", s.sample_kind.c_str(), s.sample.c_str());
          std::printf("  >>> first %s magnitude = %.4f  => %s\n", s.sample_kind == "PTS" ? "|xyz|" : "dist",
                      s.sample_first_dist_or_xyz, s.sample_first_dist_or_xyz > 50 ? "MILLIMETRES (range_scale~0.001)" : "METRES (range_scale~1)");
        }
        if (!raw.empty()) std::printf("  raw -> %s\n", raw.c_str());
        return 0;
      }
      if (sub == "calib") {
        std::string out = (argc >= 5 && argv[4][0] != '-') ? argv[4] : "calibration_results.json";
        int port = (argc >= 6) ? std::stoi(argv[5]) : 4000;
        lidar::device::DeviceClient cli(ip, port);
        lidar::CalibParams cp;
        if (!cli.getCalibration(cp, err)) { std::printf("error: %s\n", err.c_str()); return 1; }
        lidar::saveCalibrationParamsJson(out, cp);
        std::printf("pulled calibration from %s -> %s\n  cam fx,fy,cx,cy = %.2f %.2f %.2f %.2f\n  b2w_quat=[%.3f %.3f %.3f %.3f] scale=%.3f\n",
                    ip.c_str(), out.c_str(), cp.camera_intrinsic[0], cp.camera_intrinsic[1], cp.camera_intrinsic[2], cp.camera_intrinsic[3],
                    cp.b2w_quat.w(), cp.b2w_quat.x(), cp.b2w_quat.y(), cp.b2w_quat.z(), cp.b2w_scale);
        return 0;
      }
      return usage();
    }
  } catch (const std::exception& e) {
    std::printf("error: %s\n", e.what());
    return 1;
  }
  return usage();
}
