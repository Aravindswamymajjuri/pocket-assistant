#!/usr/bin/env bash
# Downloads the offline voice models and the sherpa-onnx library into the app before the build.
set -euo pipefail

SHERPA_VERSION=1.13.8
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download
WORK="${RUNNER_TEMP:-/tmp}/voice-models"
OUT=app/src/main/assets/models
HASHES=scripts/models.sha256
# Wake phrases must be upper case. Add one phrase per line to support more names.
WAKE_PHRASES=${WAKE_PHRASES:-"HEY ASSISTANT"}

mkdir -p "$WORK" app/libs
rm -rf "$OUT"
mkdir -p "$OUT"/kws "$OUT"/vad "$OUT"/whisper "$OUT"/piper "$OUT"/speaker

get() {
  local url=$1 name
  name=$(basename "$url")
  [ -f "$WORK/$name" ] || curl -fsSL --retry 3 -o "$WORK/$name" "$url"
}

get "$BASE/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
get "$BASE/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
get "$BASE/asr-models/silero_vad.onnx"
get "$BASE/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2"
get "$BASE/tts-models/vits-piper-en_US-lessac-medium.tar.bz2"
get "$BASE/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"

echo "--- SHA-256 of downloads"
(cd "$WORK" && sha256sum *.aar *.tar.bz2 *.onnx | sort -k2) | tee "$WORK/actual.sha256"
if [ -s "$HASHES" ]; then
  diff "$HASHES" "$WORK/actual.sha256"
  echo "All download hashes match the pinned list"
else
  echo "::warning::scripts/models.sha256 is empty, so downloads were not pinned"
fi

cp "$WORK/sherpa-onnx-$SHERPA_VERSION.aar" app/libs/

tar -xjf "$WORK/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2" -C "$WORK"
K="$WORK/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
cp "$K"/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx "$OUT/kws/encoder.onnx"
cp "$K"/decoder-epoch-12-avg-2-chunk-16-left-64.onnx "$OUT/kws/decoder.onnx"
cp "$K"/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx "$OUT/kws/joiner.onnx"
cp "$K/tokens.txt" "$OUT/kws/tokens.txt"
: > "$WORK/keywords_raw.txt"
while IFS= read -r phrase; do
  [ -n "$phrase" ] && echo "$phrase :2.0 #0.3 @${phrase// /_}" >> "$WORK/keywords_raw.txt"
done <<< "$WAKE_PHRASES"
sherpa-onnx-cli text2token --tokens "$K/tokens.txt" --tokens-type bpe --bpe-model "$K/bpe.model" \
  "$WORK/keywords_raw.txt" "$OUT/kws/keywords.txt"
echo "--- wake keywords"; cat "$OUT/kws/keywords.txt"

cp "$WORK/silero_vad.onnx" "$OUT/vad/silero_vad.onnx"

tar -xjf "$WORK/sherpa-onnx-whisper-tiny.en.tar.bz2" -C "$WORK"
W="$WORK/sherpa-onnx-whisper-tiny.en"
cp "$W/tiny.en-encoder.int8.onnx" "$OUT/whisper/encoder.onnx"
cp "$W/tiny.en-decoder.int8.onnx" "$OUT/whisper/decoder.onnx"
cp "$W/tiny.en-tokens.txt" "$OUT/whisper/tokens.txt"

tar -xjf "$WORK/vits-piper-en_US-lessac-medium.tar.bz2" -C "$WORK"
P="$WORK/vits-piper-en_US-lessac-medium"
cp "$P/en_US-lessac-medium.onnx" "$OUT/piper/model.onnx"
cp "$P/tokens.txt" "$OUT/piper/tokens.txt"
cp -r "$P/espeak-ng-data" "$OUT/piper/espeak-ng-data"

cp "$WORK/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx" "$OUT/speaker/model.onnx"
du -sh "$OUT"/* app/libs/*
