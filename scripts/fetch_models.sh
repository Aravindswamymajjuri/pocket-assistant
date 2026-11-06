#!/usr/bin/env bash
# Downloads the offline voice models and the sherpa-onnx library into the app before the build.
set -euo pipefail

SHERPA_VERSION=1.13.8
WHISPER_CPP_TAG=v1.9.5
WHISPER_CPP_COMMIT=d1be6fde11ac6e0407606b4e42fe72d34add8037
BASE=https://github.com/k2-fsa/sherpa-onnx/releases/download
WORK="${RUNNER_TEMP:-/tmp}/voice-models"
OUT=app/src/main/assets/models
HASHES=scripts/models.sha256
# Wake phrases must be upper case. Add one phrase per line to support more names.
WAKE_PHRASES=${WAKE_PHRASES:-"HEY ASSISTANT"}

mkdir -p "$WORK" app/libs
rm -rf "$OUT"
mkdir -p "$OUT"/kws "$OUT"/vad "$OUT"/whisper "$OUT"/piper "$OUT"/piper-hi "$OUT"/speaker

if [ ! -d app/src/main/cpp/whisper.cpp ]; then
  git clone --quiet --depth 1 -b "$WHISPER_CPP_TAG" https://github.com/ggml-org/whisper.cpp app/src/main/cpp/whisper.cpp
fi
[ "$(git -C app/src/main/cpp/whisper.cpp rev-parse HEAD)" = "$WHISPER_CPP_COMMIT" ]

get() {
  local url=$1 name
  name=$(basename "$url")
  [ -f "$WORK/$name" ] || curl -fsSL --retry 3 -o "$WORK/$name" "$url"
}

get "$BASE/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"
get "$BASE/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
get "$BASE/asr-models/silero_vad.onnx"
get "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin"
get "$BASE/tts-models/vits-piper-en_US-lessac-medium.tar.bz2"
get "$BASE/tts-models/vits-piper-hi_IN-pratham-medium.tar.bz2"
get "$BASE/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"

echo "--- SHA-256 of downloads"
(cd "$WORK" && sha256sum *.aar *.tar.bz2 *.onnx *.bin | sort -k2) | tee "$WORK/actual.sha256"
if diff "$HASHES" "$WORK/actual.sha256"; then
  echo "All download hashes match the pinned list"
else
  echo "::warning::Download list differs from scripts/models.sha256"
  # A pinned file that changed always fails. A new file is allowed only until it is pinned.
  (cd "$WORK" && sha256sum --check --ignore-missing "$OLDPWD/$HASHES")
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
# Lets the app turn a name you type into wake-word tokens, with no network access.
python3 - "$K/bpe.model" "$OUT/kws/bpe.vocab" <<'PY'
import sentencepiece, sys
sp = sentencepiece.SentencePieceProcessor(model_file=sys.argv[1])
with open(sys.argv[2], "w", encoding="utf-8") as out:
    for i in range(sp.get_piece_size()):
        out.write(f"{sp.id_to_piece(i)}\t{sp.get_score(i)}\n")
PY
python3 - "$K/bpe.model" "$OUT/kws/bpe_fixture.txt" <<'PY'
import sentencepiece, sys
sp = sentencepiece.SentencePieceProcessor(model_file=sys.argv[1])
with open(sys.argv[2], "w", encoding="utf-8") as out:
    for phrase in ["HEY ASSISTANT", "HEY NOVA", "HEY MITRA", "HEY JARVIS", "HEY ARAVIND", "HEY SITA RAM"]:
        out.write(phrase + "\t" + " ".join(sp.encode(phrase, out_type=str)) + "\n")
PY

cp "$WORK/silero_vad.onnx" "$OUT/vad/silero_vad.onnx"

cp "$WORK/ggml-base-q5_1.bin" "$OUT/whisper/ggml-base-q5_1.bin"

tar -xjf "$WORK/vits-piper-en_US-lessac-medium.tar.bz2" -C "$WORK"
P="$WORK/vits-piper-en_US-lessac-medium"
cp "$P/en_US-lessac-medium.onnx" "$OUT/piper/model.onnx"
cp "$P/tokens.txt" "$OUT/piper/tokens.txt"
cp -r "$P/espeak-ng-data" "$OUT/piper/espeak-ng-data"

tar -xjf "$WORK/vits-piper-hi_IN-pratham-medium.tar.bz2" -C "$WORK"
H="$WORK/vits-piper-hi_IN-pratham-medium"
cp "$H/hi_IN-pratham-medium.onnx" "$OUT/piper-hi/model.onnx"
cp "$H/tokens.txt" "$OUT/piper-hi/tokens.txt"

cp "$WORK/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx" "$OUT/speaker/model.onnx"
du -sh "$OUT"/* app/libs/*
