# gomob FireRedASR2 语音转文字服务

默认方案是 `FireRedASR2-AED + VAD + LID + Punc`：准确度优先、支持词级时间戳与置信度，适合消息语音转文字的正式链路。服务不提供假返回；模型目录或 FireRedASR2S 代码缺失时启动失败。

## 启动

推荐用仓库脚本，环境、模型和日志都落在 `.dev/`：

```bash
server/asr_service/scripts/prepare_env.sh
GOMOB_FIRERED_MODEL_PROVIDER=huggingface server/asr_service/scripts/download_models.sh
server/asr_service/scripts/run.sh
```

当前本机已验证的运行参数写在 `.dev/asr_service/env.sh`：

```bash
export GOMOB_ASR_PYTHON=/root/lilw/gomob/.dev/asr_service/venv/bin/python
export GOMOB_FIRERED_ASR2S_REPO=/root/lilw/gomob/.dev/vendor/FireRedASR2S
export GOMOB_FIRERED_MODEL_ROOT=/root/lilw/gomob/.dev/asr_models/pretrained_models
export CUDA_VISIBLE_DEVICES=2
```

模型体积约 9.2GB，运行时 `FireRedASR2-AED + LID + Punc` 在 RTX 2080 Ti 上占用约 9GB 显存。

手动启动等价流程：

```bash
cd server/asr_service
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
pip install -r /path/to/FireRedASR2S/requirements.txt
export GOMOB_FIRERED_ASR2S_REPO=/path/to/FireRedASR2S
export GOMOB_FIRERED_MODEL_ROOT=/path/to/pretrained_models
python app.py
```

服务端 worker 连接它：

```bash
export GOMOB_ASR_URL=http://127.0.0.1:18091
go run ./cmd/devserver
```

本地验收：

```bash
./dev.sh harness asr_transcript_queue
```

有模型环境下，harness 会上传真实中文语音样本，等待消息 payload 的 `transcript_status=done`，并校验转写文本包含 `你好世界`。

关键模型目录可单独覆盖：

```bash
export GOMOB_FIRERED_ASR_MODEL_DIR=/models/FireRedASR2-AED
export GOMOB_FIRERED_VAD_DIR=/models/FireRedVAD/VAD
export GOMOB_FIRERED_LID_DIR=/models/FireRedLID
export GOMOB_FIRERED_PUNC_DIR=/models/FireRedPunc
```

`FireRedASR2-AED` 单段音频建议不超过 60 秒；App 消息语音超过该限制时应拆段或拒绝发送。
