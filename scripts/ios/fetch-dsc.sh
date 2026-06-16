#!/usr/bin/env bash
#
# fetch-dsc.sh — baixa+extrai o dyld_shared_cache do iOS para o Vortex-iOS (Path B).
#
# Baixa um firmware iOS DA APPLE (você traz o seu — "bring-your-own-dylibs"), extrai o
# dyld_shared_cache arm64e e o divide em sub-caches/dylibs prontos para o Vortex-iOS mapear.
# Ao final, GERA o vortex-ios.json (com os caminhos da SUA máquina) que o subsistema
# vortexdbg-ios-dsc lê — você não edita nada à mão.
#
# IMPORTANTE (legal): isto NÃO redistribui nada da Apple. Você roda localmente apontando para
# um firmware que VOCÊ baixa da Apple. Os arquivos extraídos e o JSON gerado NÃO vão para o
# repo (estão no .gitignore). Só ESTE script é versionado.
#
# Requisito: brew install blacktop/tap/ipsw
#
# Uso (a partir de um clone do repo):
#   scripts/ios/fetch-dsc.sh                          # default: iPhone11,2 / iOS 18.7.9 / arm64e
#   scripts/ios/fetch-dsc.sh --device iPhone16,1 --version 18.1.1
#   MODE=full scripts/ios/fetch-dsc.sh                # baixa o IPSW inteiro e extrai local
#
# Flags / env:
#   --device <id>    iOS device (default iPhone11,2 = iPhone XS, arm64e)
#   --version <v>    versão iOS (default 18.7.9). Precisa estar ASSINADA p/ o device.
#   --arch <a>       arquitetura do DSC (default arm64e)
#   --out <dir>      onde baixar/extrair (default: <dir-do-script>/dsc — gitignorado)
#   --json <path>    onde gravar o vortex-ios.json (default: <dir-do-script>/vortex-ios.json)
#   --mode <m>       remote (default, ~4-5GB, só o DSC) | full (~8-9GB, IPSW inteiro)
#
set -euo pipefail

DEVICE="${DEVICE:-iPhone11,2}"
VERSION="${VERSION:-18.7.9}"
ARCH="${ARCH:-arm64e}"
MODE="${MODE:-remote}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="${OUT:-$SCRIPT_DIR/dsc}"
JSON_OUT="${JSON_OUT:-$SCRIPT_DIR/vortex-ios.json}"

while [[ "${1:-}" == --* ]]; do
  case "$1" in
    --device)  DEVICE="$2"; shift 2 ;;
    --version) VERSION="$2"; shift 2 ;;
    --arch)    ARCH="$2"; shift 2 ;;
    --out)     OUT="$2"; shift 2 ;;
    --json)    JSON_OUT="$2"; shift 2 ;;
    --mode)    MODE="$2"; shift 2 ;;
    -h|--help) sed -n '2,33p' "$0"; exit 0 ;;
    *) echo "flag desconhecida: $1"; exit 1 ;;
  esac
done

command -v ipsw >/dev/null 2>&1 || {
  echo "ERRO: 'ipsw' não encontrado. Instale: brew install blacktop/tap/ipsw"; exit 1; }

ROOTFS="$OUT/rootfs-$VERSION"
mkdir -p "$OUT" "$ROOTFS"
cd "$OUT"

echo "=========================================================================="
echo " vortex fetch-dsc"
echo "   device : $DEVICE"
echo "   iOS    : $VERSION"
echo "   arch   : $ARCH"
echo "   modo   : $MODE   (remote = só o DSC ~4-5GB | full = IPSW inteiro ~8-9GB)"
echo "   saída  : $OUT"
echo "   json   : $JSON_OUT"
echo "=========================================================================="

# -------------------------------------------------------------------------
# 1) Obter o dyld_shared_cache
# -------------------------------------------------------------------------
if [[ "$MODE" == "remote" ]]; then
  echo ">> [1/3] extração REMOTA do dyld_shared_cache ($ARCH)..."
  echo "   (--fcs-keys lida com o Cryptex/AEA do iOS 16+; pode demorar na fase de metadata)"
  ipsw download ipsw --device "$DEVICE" --version "$VERSION" \
       --dyld --dyld-arch "$ARCH" --fcs-keys --confirm
else
  echo ">> [1/3] baixando o IPSW completo e extraindo o DSC localmente..."
  ipsw download ipsw --device "$DEVICE" --version "$VERSION" --confirm
  IPSW_FILE="$(ls -t ./*.ipsw 2>/dev/null | head -1 || true)"
  [[ -n "$IPSW_FILE" ]] || { echo "ERRO: IPSW não baixado."; exit 1; }
  echo "   extraindo DSC de $IPSW_FILE"
  ipsw extract --dyld --dyld-arch "$ARCH" -o "$OUT" "$IPSW_FILE"
fi

# localizar o arquivo do cache (nome/path varia por versão)
CACHE="$(find "$OUT" -maxdepth 6 -type f -iname "dyld_shared_cache_${ARCH}" 2>/dev/null | head -1 || true)"
if [[ -z "$CACHE" ]]; then
  CACHE="$(find "$OUT" -maxdepth 6 -type f -iname 'dyld_shared_cache_*' ! -iname '*.*map' 2>/dev/null | head -1 || true)"
fi
[[ -n "$CACHE" ]] || { echo "ERRO: dyld_shared_cache não encontrado em $OUT (cheque o output acima)."; exit 1; }
echo ">> cache: $CACHE"

# -------------------------------------------------------------------------
# 2) Dividir o cache em sub-caches/dylibs (Xcode dsc_extractor via ipsw)
# -------------------------------------------------------------------------
echo ">> [2/3] dividindo o cache -> $ROOTFS"
ipsw dyld split -o "$ROOTFS" "$CACHE"

# -------------------------------------------------------------------------
# 3) Gerar o vortex-ios.json com os caminhos DESTA máquina
# -------------------------------------------------------------------------
# O subsistema (VortexIosConfig) acha o cache varrendo os subdirs de rootfs.parentFile,
# que é justamente $OUT (onde o `ipsw download --dyld` deixou o <build>__<device>/).
echo ">> [3/3] gerando $JSON_OUT"
cat > "$JSON_OUT" <<EOF
{
  "rootfs": "$ROOTFS",
  "version": "$VERSION",
  "arch": "$ARCH"
}
EOF

echo "=========================================================================="
echo " PRONTO."
echo "   cache + sub-caches em: $OUT"
echo "   config gerada        : $JSON_OUT"
echo ""
echo " Os demos do vortexdbg-ios-dsc já acham o JSON automaticamente se você rodar a"
echo " partir da raiz do repo (ele procura scripts/ios/vortex-ios.json). Se mover o JSON,"
echo " aponte com a env var:"
echo ""
echo "     export VORTEX_IOS_CONFIG=\"$JSON_OUT\""
echo ""
echo " NÃO comite os arquivos baixados nem o vortex-ios.json (são da Apple / têm caminhos"
echo " da sua máquina) — já estão no .gitignore."
echo "=========================================================================="
