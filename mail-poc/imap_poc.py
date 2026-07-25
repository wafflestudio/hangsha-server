#!/usr/bin/env python3
"""
서울대 대량메일발송 시스템(ThunderMail) 메일 수집 POC.

확인된 사실:
  - 학부생 @snu.ac.kr 메일함은 Google Workspace (gov-dooray 가 받아 Gmail 로 자동전달)
  - 대량메일은 X-Mailer 로 식별된다: ThunderMail5.0 / bizenic_sender
  - 본문 링크·포스터는 letter2.snu.ac.kr (= 대량메일발송 시스템) 호스팅
  - 본문 링크에 수신자 이메일이 base64(EMA=)로 박혀 있어 반드시 스크러빙해야 한다

메일함은 항상 readonly로 열고 BODY.PEEK만 쓴다. 읽음 처리되지 않는다.
표준 라이브러리만 사용한다.
"""

from __future__ import annotations

import argparse
import codecs
import csv
import email
import email.utils
import imaplib
import json
import os
import re
import ssl
import sys
import urllib.parse
from email.header import decode_header, make_header
from email.message import Message
from pathlib import Path
from zoneinfo import ZoneInfo

SEOUL = ZoneInfo("Asia/Seoul")

# 대량메일발송 시스템이 붙이는 X-Mailer 값 (실측). 제목 규칙보다 안정적이다.
BULK_MAILERS = ("thundermail", "bizenic")
BULK_HOST = "letter2.snu.ac.kr"

# snu.ac.kr 은 메일 시스템이 소속에 따라 갈린다 (SPF 에 3개가 다 들어있다):
#   Dooray(snu.gov-dooray.com)  -> 교직원·대학원생 전용. 학부생 계정 없음.
#   Microsoft 365               -> 테넌트 존재. IMAP 은 XOAUTH2 만 되므로 앱 비밀번호 불가.
#   Google Workspace            -> 학부생. imap.gmail.com, AUTH=PLAIN + X-GM-EXT-1 지원. ← 이 계정
# 다른 시스템을 확인할 때는 SNU_IMAP_HOST 로 덮어쓴다.
IMAP_HOST = os.environ.get("SNU_IMAP_HOST", "imap.gmail.com")
IMAP_PORT = int(os.environ.get("SNU_IMAP_PORT", "993"))

HERE = Path(__file__).resolve().parent
OUT_DIR = HERE / "out"

# 대량메일 여부를 판단할 단서가 될 만한 헤더들.
# 어떤 게 실제로 붙어오는지 모르므로 일단 전부 찍어보고 고른다.
SIGNAL_HEADERS = [
    "From",
    "Sender",
    "Return-Path",
    "Reply-To",
    "List-Id",
    "List-Unsubscribe",
    "Precedence",
    "Auto-Submitted",
    "X-Mailer",
    "X-Original-Sender",
]

FETCH_HEADER_FIELDS = "DATE FROM TO SUBJECT SENDER RETURN-PATH REPLY-TO LIST-ID LIST-UNSUBSCRIBE PRECEDENCE AUTO-SUBMITTED X-MAILER X-ORIGINAL-SENDER MESSAGE-ID"


# --------------------------------------------------------------------------
# 자격증명
# --------------------------------------------------------------------------

def load_env_file() -> None:
    """mail-poc/.env 를 읽어 os.environ에 채운다 (이미 있는 값은 유지)."""
    env_path = HERE / ".env"
    if not env_path.exists():
        return
    for line in env_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        os.environ.setdefault(key.strip(), value.strip().strip("'\""))


PASSWORD_KEYS = ("SNU_MAIL_APP_PASSWORD", "SNU_MAIL_APP", "SNU_MAIL_PASSWORD")


def credentials() -> tuple[str, str]:
    user = os.environ.get("SNU_MAIL_USER", "").strip()

    # 앱 비밀번호 전용 키를 먼저 본다. 일반 로그인 비밀번호는 Gmail IMAP 에서
    # 어차피 거부되므로, 둘 다 있으면 앱 비밀번호를 쓰는 게 맞다.
    password, used_key = "", ""
    for key in PASSWORD_KEYS:
        # Google 은 앱 비밀번호를 'abcd efgh ijkl mnop' 처럼 공백을 넣어 보여준다.
        # 그대로 붙여넣으면 인증이 실패하므로 모든 공백을 제거한다.
        candidate = "".join(os.environ.get(key, "").split())
        if candidate:
            password, used_key = candidate, key
            break

    if not user or not password:
        sys.exit(
            f"SNU_MAIL_USER 와 비밀번호({' 또는 '.join(PASSWORD_KEYS)}) 가 필요합니다.\n"
            "  mail-poc/.env 를 만들거나 환경변수로 넘기세요. (README 참고)"
        )

    print(f"[CRED] user={user} password_from={used_key} ({len(password)}자)")
    if len(password) != 16 and used_key == "SNU_MAIL_PASSWORD":
        print("[CRED] 경고: Gmail 앱 비밀번호는 16자다. 일반 로그인 비밀번호는 IMAP 에서 거부된다.")
    return user, password


# --------------------------------------------------------------------------
# IMAP 유틸
# --------------------------------------------------------------------------

def connect() -> imaplib.IMAP4_SSL:
    user, password = credentials()
    print(f"[CONNECT] {IMAP_HOST}:{IMAP_PORT} as {user}")
    conn = imaplib.IMAP4_SSL(IMAP_HOST, IMAP_PORT, ssl_context=ssl.create_default_context())
    try:
        conn.login(user, password)
    except imaplib.IMAP4.error as e:
        detail = e.args[0].decode() if e.args and isinstance(e.args[0], bytes) else str(e)
        sys.exit(
            f"[LOGIN FAILED] {detail}\n"
            "  Gmail 은 일반 로그인 비밀번호를 IMAP 에 받지 않는다. 확인 순서:\n"
            "   1. 2단계 인증이 켜져 있는지 (앱 비밀번호 발급 전제조건)\n"
            "   2. https://myaccount.google.com/apppasswords 에서 앱 비밀번호를 발급했는지\n"
            "      → 16자리를 공백 없이 .env 에 넣는다\n"
            "   3. 메뉴가 안 보이면 SNU Workspace 관리자가 앱 비밀번호/IMAP 을 차단한 것\n"
            "      → 이 경우 Gmail API(OAuth2) 로 가야 한다"
        )
    print("[LOGIN OK]")
    # imaplib 의 capabilities 는 bytes 가 아니라 str 튜플이다.
    if "X-GM-EXT-1" in conn.capabilities:
        print("[GMAIL] X-GM-EXT-1 지원 → Gmail 검색 문법(X-GM-RAW) / 라벨 조회 가능")
    return conn


def decode_mutf7(raw: str) -> str:
    """IMAP modified UTF-7 폴더명 디코딩 (한글 폴더명 대응)."""

    def _chunk(m: re.Match[str]) -> str:
        body = m.group(1)
        if body == "":
            return "&"  # "&-" 는 리터럴 '&'
        # mUTF-7 은 UTF-7 의 '+' 를 '&' 로, '/' 를 ',' 로 바꾼 것.
        # 되돌려서 표준 utf-7 코덱에 넘긴다.
        try:
            return ("+" + body.replace(",", "/") + "-").encode("ascii").decode("utf-7")
        except Exception:
            return m.group(0)

    return re.sub(r"&([^-]*)-", _chunk, raw)


def _register_korean_charset_aliases() -> None:
    """'ks_c_5601-1987' 같은 라벨은 python 코덱 이름이 아니라 lookup 이 실패한다.
    한국 메일에서 흔하므로 cp949(euc-kr 상위집합)로 매핑한다."""
    aliases = {"ks-c-5601-1987", "ks-c-5601", "ksc5601", "ksc-5601", "ks-x-1001"}

    def search(name: str):
        if name.replace("_", "-").lower() in aliases:
            return codecs.lookup("cp949")
        return None

    codecs.register(search)


_register_korean_charset_aliases()

# euc-kr 로 선언됐지만 실제로는 cp949 확장문자를 쓰는 메일이 많아 cp949 를 먼저 시도한다.
CHARSET_FALLBACKS = ("utf-8", "cp949", "euc-kr", "latin-1")


def decode_bytes(data: bytes, charset: str | None) -> str:
    for candidate in ((charset,) if charset else ()) + CHARSET_FALLBACKS:
        try:
            return data.decode(candidate)
        except (UnicodeDecodeError, LookupError):
            continue
    return data.decode("utf-8", errors="replace")


def decode_header_value(raw: str | None) -> str:
    """헤더 디코딩. 두 가지를 모두 처리한다.

    1. RFC2047 encoded-word:  =?UTF-8?B?...?= / =?EUC-KR?B?...?=
    2. 인코딩 없이 8bit 한글을 그대로 넣은 헤더 (SNU 대량메일에 실제로 존재)
       BytesParser 가 ascii+surrogateescape 로 읽어놨으므로 원본 바이트를 복원해 디코딩한다.
    """
    if not raw:
        return ""
    try:
        parts = decode_header(raw)
    except Exception:
        parts = [(raw, None)]

    out = []
    for text, charset in parts:
        if isinstance(text, bytes):
            out.append(decode_bytes(text, charset))
        elif any("\udc80" <= ch <= "\udcff" for ch in text):
            out.append(decode_bytes(text.encode("utf-8", "surrogateescape"), charset))
        else:
            out.append(text)
    return "".join(out)


def fetch_header_message(conn: imaplib.IMAP4_SSL, uid: bytes) -> Message | None:
    """읽음 처리 없이 헤더만 가져온다."""
    typ, data = conn.uid("fetch", uid, f"(BODY.PEEK[HEADER.FIELDS ({FETCH_HEADER_FIELDS})])")
    if typ != "OK":
        return None
    for part in data:
        if isinstance(part, tuple) and part[1]:
            return email.message_from_bytes(part[1])
    return None


def fetch_full_message(conn: imaplib.IMAP4_SSL, uid: bytes) -> Message | None:
    """읽음 처리 없이 전체 원문을 가져온다."""
    typ, data = conn.uid("fetch", uid, "(BODY.PEEK[])")
    if typ != "OK":
        return None
    for part in data:
        if isinstance(part, tuple) and part[1]:
            return email.message_from_bytes(part[1])
    return None


def search_uids(
    conn: imaplib.IMAP4_SSL,
    folder: str,
    days: int,
    limit: int,
    gmail_query: str | None = None,
) -> list[bytes]:
    typ, _ = conn.select(folder, readonly=True)  # readonly: 절대 상태 변경 안 함
    if typ != "OK":
        sys.exit(f"[SELECT FAILED] folder={folder}")

    if gmail_query:
        uids = gmail_raw_search(conn, gmail_query)
        if uids is not None:
            print(f"[SEARCH] X-GM-RAW {gmail_query!r} matched={len(uids)}")
            return uids[-limit:] if limit > 0 else uids
        print("[SEARCH] X-GM-RAW 실패 → 기간 검색으로 대체")

    criteria = ["ALL"] if days <= 0 else ["SINCE", imap_date(days)]
    typ, data = conn.uid("search", None, *criteria)
    if typ != "OK":
        sys.exit(f"[SEARCH FAILED] folder={folder}")

    uids = data[0].split()
    print(f"[SEARCH] folder={folder} criteria={' '.join(criteria)} matched={len(uids)}")
    return uids[-limit:] if limit > 0 else uids


def gmail_raw_search(conn: imaplib.IMAP4_SSL, query: str) -> list[bytes] | None:
    """Gmail 검색 문법(X-GM-RAW)으로 검색. 실패하면 None 을 돌려 호출자가 대체하게 한다.

    한글 쿼리는 ASCII 로 못 보내므로 IMAP literal + CHARSET UTF-8 로 넘긴다.
    """
    if "X-GM-EXT-1" not in conn.capabilities:
        print("[SEARCH] 서버가 X-GM-EXT-1 미지원")
        return None
    try:
        if query.isascii():
            typ, data = conn.uid("search", None, "X-GM-RAW", f'"{query}"')
        else:
            conn.literal = query.encode("utf-8")
            typ, data = conn.uid("search", "CHARSET", "UTF-8", "X-GM-RAW")
        if typ != "OK" or not data or data[0] is None:
            return None
        return data[0].split()
    except imaplib.IMAP4.error as e:
        print(f"[SEARCH] X-GM-RAW error: {e}")
        return None


def imap_date(days: int) -> str:
    """IMAP SINCE 는 '01-Jan-2026' 형식을 요구한다."""
    import datetime

    day = datetime.date.today() - datetime.timedelta(days=days)
    return day.strftime("%d-%b-%Y")


# --------------------------------------------------------------------------
# 커맨드: probe
# --------------------------------------------------------------------------

def cmd_probe(args: argparse.Namespace) -> None:
    conn = connect()
    try:
        print(f"\n[CAPABILITY] {' '.join(conn.capabilities)}")

        typ, folders = conn.list()
        print(f"\n[FOLDERS] {len(folders) if typ == 'OK' else 0}개")
        for raw in folders or []:
            line = raw.decode("latin-1")
            name = line.split(' "/" ')[-1].strip('"') if ' "/" ' in line else line
            print(f"  - {decode_mutf7(name)}")

        typ, data = conn.select("INBOX", readonly=True)
        if typ == "OK":
            print(f"\n[INBOX] 총 {data[0].decode()}통")
    finally:
        logout(conn)


# --------------------------------------------------------------------------
# 커맨드: headers — 대량메일을 식별할 헤더 단서를 찾는다
# --------------------------------------------------------------------------

def cmd_headers(args: argparse.Namespace) -> None:
    conn = connect()
    try:
        uids = search_uids(conn, args.folder, args.days, args.limit, getattr(args, "query", None))
        if not uids:
            print("조건에 맞는 메일이 없습니다. --days 를 늘려보세요.")
            return

        rows = []
        for uid in uids:
            msg = fetch_header_message(conn, uid)
            if msg is None:
                continue
            row = {"uid": uid.decode()}
            for header in SIGNAL_HEADERS + ["Date", "Subject"]:
                value = decode_header_value(msg.get(header))
                if value:
                    row[header] = value
            rows.append(row)

        print(f"\n{'=' * 100}")
        print("최근 메일 목록 (uid / date / from / subject)")
        print("=" * 100)
        for row in rows:
            print(f"[{row['uid']:>6}] {row.get('Date', '')[:31]:31} {row.get('From', '')[:34]:34} {row.get('Subject', '')[:60]}")

        print(f"\n{'=' * 100}")
        print("헤더 출현 빈도 — 대량메일 식별에 쓸 헤더 고르기용")
        print("=" * 100)
        for header in SIGNAL_HEADERS:
            present = [r for r in rows if header in r]
            if not present:
                print(f"  {header:22} 0/{len(rows)}  (없음)")
                continue
            distinct = sorted({r[header] for r in present})
            print(f"  {header:22} {len(present)}/{len(rows)}  고유값 {len(distinct)}개")
            for value in distinct[:5]:
                print(f"{'':26}· {value[:80]}")

        OUT_DIR.mkdir(exist_ok=True)
        out_file = OUT_DIR / "headers.json"
        out_file.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n[SAVED] {out_file}")
        print("→ 대량메일로 보이는 메일의 uid 를 골라 `raw <uid>` / `dump <uid>` 로 확인하세요.")
    finally:
        logout(conn)


# --------------------------------------------------------------------------
# 커맨드: raw — 특정 메일의 전체 헤더 + MIME 구조
# --------------------------------------------------------------------------

def cmd_raw(args: argparse.Namespace) -> None:
    conn = connect()
    try:
        conn.select(args.folder, readonly=True)
        msg = fetch_full_message(conn, args.uid.encode())
        if msg is None:
            sys.exit(f"uid={args.uid} 메일을 가져오지 못했습니다.")

        print(f"\n{'=' * 100}\n전체 헤더 (uid={args.uid})\n{'=' * 100}")
        for key, value in msg.items():
            print(f"{key}: {decode_header_value(value)[:300]}")

        print(f"\n{'=' * 100}\nMIME 구조\n{'=' * 100}")
        print_structure(msg)
    finally:
        logout(conn)


def print_structure(msg: Message, depth: int = 0) -> None:
    indent = "  " * depth
    ctype = msg.get_content_type()
    if msg.is_multipart():
        print(f"{indent}+ {ctype}")
        for part in msg.get_payload():
            print_structure(part, depth + 1)
        return

    payload = msg.get_payload(decode=True) or b""
    bits = [f"{len(payload):,}B"]
    if msg.get_filename():
        bits.append(f"filename={decode_header_value(msg.get_filename())}")
    if msg.get("Content-ID"):
        bits.append(f"cid={msg.get('Content-ID')}")
    print(f"{indent}- {ctype} ({', '.join(bits)})")


# --------------------------------------------------------------------------
# 커맨드: dump — 본문 HTML / 이미지를 파일로 떨어뜨린다
# --------------------------------------------------------------------------

def cmd_dump(args: argparse.Namespace) -> None:
    conn = connect()
    try:
        conn.select(args.folder, readonly=True)
        msg = fetch_full_message(conn, args.uid.encode())
        if msg is None:
            sys.exit(f"uid={args.uid} 메일을 가져오지 못했습니다.")

        target = OUT_DIR / f"uid-{args.uid}"
        (target / "images").mkdir(parents=True, exist_ok=True)

        headers = {key: decode_header_value(value) for key, value in msg.items()}
        (target / "headers.json").write_text(
            json.dumps(headers, ensure_ascii=False, indent=2), encoding="utf-8"
        )

        html_parts, text_parts, images = [], [], 0
        for part in msg.walk():
            if part.is_multipart():
                continue
            ctype = part.get_content_type()
            payload = part.get_payload(decode=True) or b""

            if ctype == "text/html":
                html_parts.append(decode_text(part, payload))
            elif ctype == "text/plain":
                text_parts.append(decode_text(part, payload))
            elif ctype.startswith("image/"):
                images += 1
                name = decode_header_value(part.get_filename()) or f"image-{images}.{ctype.split('/')[-1]}"
                (target / "images" / sanitize(name)).write_bytes(payload)

        if html_parts:
            (target / "body.html").write_text("\n<!-- part -->\n".join(html_parts), encoding="utf-8")
        if text_parts:
            (target / "body.txt").write_text("\n--- part ---\n".join(text_parts), encoding="utf-8")

        print(f"\n[DUMPED] {target}")
        print(f"  headers.json  ({len(headers)} headers)")
        print(f"  body.html     {'있음' if html_parts else '없음 ← 텍스트 본문 없는 포스터 메일일 수 있음'}")
        print(f"  body.txt      {'있음' if text_parts else '없음'}")
        print(f"  images/       {images}개")
        print("\n→ body.html 이 기존 EliceEventParserClient 의 mainContentHtml 로 그대로 들어갈 후보입니다.")
        if not html_parts and not text_parts and images:
            print("→ 텍스트가 없고 이미지만 있으면 OCR 없이는 파싱 불가입니다.")
    finally:
        logout(conn)


def decode_text(part: Message, payload: bytes) -> str:
    return decode_bytes(payload, part.get_content_charset())


def sanitize(name: str) -> str:
    return re.sub(r"[^\w.\-가-힣]", "_", name)[:80] or "unnamed"


def logout(conn: imaplib.IMAP4_SSL) -> None:
    try:
        conn.logout()
    except Exception:
        pass


# --------------------------------------------------------------------------
# 대량메일 추출 — 개인정보 스크러빙 / 추적 URL 언랩 / 본문 텍스트화
# --------------------------------------------------------------------------

# 수신자를 식별하는 쿼리 파라미터. EMA 는 base64(이메일)이 그대로 들어있다.
PERSONAL_PARAMS = {"ema", "email", "mail", "recipient", "rcpt"}


def is_bulk_mail(mailer: str) -> bool:
    lowered = mailer.lower()
    return any(m in lowered for m in BULK_MAILERS)


def scrub_url(url: str) -> str:
    """URL 에서 수신자 식별 파라미터를 제거한다. 못 지우면 URL 자체를 버린다."""
    parts = urllib.parse.urlsplit(url)
    if not parts.query:
        return url

    kept = [
        (key, value)
        for key, value in urllib.parse.parse_qsl(parts.query, keep_blank_values=True)
        if key.lower() not in PERSONAL_PARAMS and not decodes_to_email(value)
    ]
    return urllib.parse.urlunsplit(
        parts._replace(query=urllib.parse.urlencode(kept))
    )


def decodes_to_email(value: str) -> bool:
    """base64 로 감싼 이메일인지 확인 (EMA 외의 파라미터명에 숨어있을 수 있다)."""
    if "@" in value:
        return True
    if len(value) < 12 or len(value) % 4:
        return False
    try:
        import base64

        return "@" in base64.b64decode(value, validate=True).decode("ascii", "ignore")
    except Exception:
        return False


def unwrap_tracking_url(url: str) -> str | None:
    """letter2 추적 링크(response.do?...&URL=<실제주소>)에서 목적지를 꺼낸다.

    URL 값이 '#' 같은 앵커면 실제 목적지가 없다는 뜻이므로 None.
    """
    if BULK_HOST not in url or "response.do" not in url:
        return None
    target = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query).get("URL", [""])[0]
    target = urllib.parse.unquote(target)
    return target if target.startswith("http") else None


def collect_links(html_text: str) -> list[str]:
    out: list[str] = []
    for raw in re.findall(r'href="([^"]+)"', html_text):
        raw = raw.replace("&amp;", "&")
        # 수신거부 링크는 계정 해시가 들어있고 행사 정보와 무관하므로 통째로 버린다.
        if "reject_list" in raw or "unsubscribe" in raw.lower():
            continue
        resolved = unwrap_tracking_url(raw)
        if resolved is None:
            # 추적 래퍼인데 목적지가 없으면(앵커) 버린다. 아니면 원본 링크를 쓴다.
            if BULK_HOST in raw and "response.do" in raw:
                continue
            resolved = raw
        if not resolved.startswith("http"):
            continue
        cleaned = scrub_url(resolved)
        if cleaned not in out:
            out.append(cleaned)
    return out


TEXT_URL_RE = re.compile(r"""https?://[^\s<>"'\)\]}]+""")


def collect_text_urls(text: str) -> list[str]:
    """본문 텍스트에 평문으로 적힌 URL 을 걷는다.

    대량메일의 '신청' 버튼은 대개 letter2 추적 래퍼(URL=# 형태)로 감싸져 있어서
    href 만 보면 실제 목적지를 알 수 없다. 반면 본문에는 forms.gle / extra.snu 등
    실제 주소가 평문으로 적혀 있는 경우가 많아 이쪽이 더 잘 회수된다.
    """
    out: list[str] = []
    for raw in TEXT_URL_RE.findall(text):
        cleaned = scrub_url(raw.rstrip(".,;:)]}»”’\"'"))
        if BULK_HOST in cleaned:  # 추적 래퍼 / UI 장식
            continue
        if cleaned not in out:
            out.append(cleaned)
    return out


def collect_posters(html_text: str) -> list[str]:
    """UI 장식(/images/btn_01.jpg 등)을 빼고 업로드된 포스터만 고른다."""
    return [
        src.replace("&amp;", "&")
        for src in re.findall(r'src="([^"]+)"', html_text)
        if "/upload/massmail/" in src
    ]


def html_to_text(html_text: str) -> str:
    body = re.sub(r"(?is)<(script|style).*?</\1>", " ", html_text)
    body = re.sub(r"(?i)<br\s*/?>|</(p|div|tr|li|h[1-6]|table)>", "\n", body)
    body = re.sub(r"<[^>]+>", " ", body)
    import html as html_mod

    body = html_mod.unescape(body)
    lines = [re.sub(r"[ \t\xa0]+", " ", line).strip() for line in body.splitlines()]
    return "\n".join(line for line in lines if line)


def split_subject(subject: str) -> tuple[str, str]:
    """'[경력개발센터]현대자동차 설명회 안내' -> ('경력개발센터', '현대자동차 설명회 안내')"""
    match = re.match(r"^\s*\[([^\]]{1,40})\]\s*(.*)$", subject)
    return (match.group(1).strip(), match.group(2).strip()) if match else ("", subject.strip())


def extract_body_html(msg: Message) -> str:
    html_parts, text_parts = [], []
    for part in msg.walk():
        if part.is_multipart():
            continue
        payload = part.get_payload(decode=True) or b""
        if part.get_content_type() == "text/html":
            html_parts.append(decode_text(part, payload))
        elif part.get_content_type() == "text/plain":
            text_parts.append(decode_text(part, payload))
    return "\n".join(html_parts) if html_parts else "\n".join(text_parts)


def mail_to_row(uid: str, gmail_msgid: str, msg: Message) -> dict[str, str]:
    subject = decode_header_value(msg.get("Subject"))
    category, title = split_subject(subject)
    from_name, from_email = email.utils.parseaddr(decode_header_value(msg.get("From")))

    date_iso = ""
    if msg.get("Date"):
        try:
            date_iso = (
                email.utils.parsedate_to_datetime(msg["Date"])
                .astimezone(SEOUL)
                .isoformat(timespec="seconds")
            )
        except (TypeError, ValueError):
            date_iso = ""

    body_html = extract_body_html(msg)
    body_text = html_to_text(body_html)

    links = collect_links(body_html)
    for url in collect_text_urls(body_text):
        if url not in links:
            links.append(url)

    return {
        "uid": uid,
        "gmail_msgid": gmail_msgid,
        "date": date_iso,
        "mailer": decode_header_value(msg.get("X-Mailer")),
        "from_name": from_name.strip(),
        "from_email": from_email,
        "category": category,
        "title": title,
        "poster_url": " | ".join(collect_posters(body_html)),
        "apply_links": " | ".join(links),
        "body_chars": str(len(body_text)),
        "body_text": body_text[:8000],
    }


CSV_COLUMNS = [
    "uid",
    "gmail_msgid",
    "date",
    "mailer",
    "from_name",
    "from_email",
    "category",
    "title",
    "poster_url",
    "apply_links",
    "body_chars",
    "body_text",
]


# --------------------------------------------------------------------------
# 커맨드: csv — 대량메일만 골라 CSV 로 떨어뜨린다
# --------------------------------------------------------------------------

def cmd_csv(args: argparse.Namespace) -> None:
    conn = connect()
    try:
        typ, _ = conn.select(args.folder, readonly=True)
        if typ != "OK":
            sys.exit(f"[SELECT FAILED] folder={args.folder}")

        candidates = search_bulk_uids(conn, args.scan)
        if not candidates:
            sys.exit("대량메일 후보를 찾지 못했습니다. --scan 을 늘려보세요.")

        rows = []
        for uid in reversed(candidates):  # 최신부터
            if len(rows) >= args.count:
                break
            gmail_msgid, msg = fetch_with_msgid(conn, uid)
            if msg is None:
                continue
            mailer = decode_header_value(msg.get("X-Mailer"))
            if not is_bulk_mail(mailer):
                continue
            row = mail_to_row(uid.decode(), gmail_msgid, msg)
            rows.append(row)
            print(f"  [{row['uid']:>6}] {row['date'][:10]} [{row['category']}] {row['title'][:50]}")

        OUT_DIR.mkdir(exist_ok=True)
        out_path = Path(args.out) if args.out else OUT_DIR / "bulkmail.csv"
        out_path.parent.mkdir(parents=True, exist_ok=True)

        # utf-8-sig: Excel 이 한글을 깨뜨리지 않게 BOM 을 넣는다.
        with out_path.open("w", encoding="utf-8-sig", newline="") as f:
            writer = csv.DictWriter(f, fieldnames=CSV_COLUMNS)
            writer.writeheader()
            writer.writerows(rows)

        print(f"\n[SAVED] {out_path}  ({len(rows)}건)")
        no_poster = sum(1 for r in rows if not r["poster_url"])
        no_link = sum(1 for r in rows if not r["apply_links"])
        thin = sum(1 for r in rows if int(r["body_chars"]) < 200)
        print(f"  포스터 없음: {no_poster}/{len(rows)}")
        print(f"  신청링크 없음: {no_link}/{len(rows)}")
        print(f"  본문 200자 미만(포스터만 있는 메일 후보): {thin}/{len(rows)}")
    finally:
        logout(conn)


def search_bulk_uids(conn: imaplib.IMAP4_SSL, scan: int) -> list[bytes]:
    """최근 UID 를 훑어 X-Mailer 로 대량메일을 고른다.

    서버측 `HEADER X-Mailer "..."` 검색은 쓰지 않는다. Gmail 의 헤더 검색은
    부분 문자열이 아니라 토큰 매칭이라 'ThunderMail5.0' 이 'thundermail' 로 안 잡힌다
    (반면 'bizenic_sender' 는 잡힌다 — 조용히 절반만 누락되는 함정).
    헤더만 배치로 받아 로컬에서 판정하는 쪽이 정확하고, 왕복도 몇 번이면 끝난다.
    """
    typ, data = conn.uid("search", None, "ALL")
    if typ != "OK" or not data or not data[0]:
        return []

    recent = data[0].split()[-scan:]
    mailers = fetch_mailers(conn, recent)
    hits = [uid for uid in recent if is_bulk_mail(mailers.get(uid, ""))]

    counts: dict[str, int] = {}
    for uid in hits:
        counts[mailers[uid]] = counts.get(mailers[uid], 0) + 1
    print(f"[SCAN] 최근 {len(recent)}통 중 대량메일 {len(hits)}통: {counts}")
    return hits


def fetch_mailers(conn: imaplib.IMAP4_SSL, uids: list[bytes], chunk: int = 200) -> dict[bytes, str]:
    """X-Mailer 헤더만 배치로 가져온다 (BODY.PEEK → 읽음 처리 없음)."""
    result: dict[bytes, str] = {}
    for start in range(0, len(uids), chunk):
        batch = uids[start:start + chunk]
        uid_set = b",".join(batch).decode()
        typ, data = conn.uid("fetch", uid_set, "(BODY.PEEK[HEADER.FIELDS (X-MAILER)])")
        if typ != "OK":
            continue
        for part in data:
            if not isinstance(part, tuple):
                continue
            prefix = part[0].decode("latin-1") if isinstance(part[0], bytes) else str(part[0])
            match = re.search(r"UID\s+(\d+)", prefix)
            if not match:
                continue
            header = email.message_from_bytes(part[1] or b"")
            result[match.group(1).encode()] = decode_header_value(header.get("X-Mailer"))
    return result


def fetch_with_msgid(conn: imaplib.IMAP4_SSL, uid: bytes) -> tuple[str, Message | None]:
    """Gmail 영구 ID(X-GM-MSGID)를 원문과 함께 가져온다. 중복 판정 키 후보."""
    typ, data = conn.uid("fetch", uid, "(X-GM-MSGID BODY.PEEK[])")
    if typ != "OK":
        return "", None

    msgid, msg = "", None
    for part in data:
        if not isinstance(part, tuple):
            continue
        prefix = part[0].decode("latin-1") if isinstance(part[0], bytes) else str(part[0])
        match = re.search(r"X-GM-MSGID\s+(\d+)", prefix)
        if match:
            msgid = match.group(1)
        if part[1]:
            msg = email.message_from_bytes(part[1])
    return msgid, msg


# --------------------------------------------------------------------------

def main() -> None:
    load_env_file()

    parser = argparse.ArgumentParser(description="SNU 메일(Dooray) IMAP 수집 POC")
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("probe", help="로그인 / CAPABILITY / 폴더 목록 확인")
    p.set_defaults(func=cmd_probe)

    p = sub.add_parser("headers", help="최근 메일 헤더를 훑어 대량메일 식별 단서 찾기")
    p.add_argument("--folder", default="INBOX")
    p.add_argument("--days", type=int, default=30, help="최근 N일 (0=전체)")
    p.add_argument("--limit", type=int, default=30, help="최대 N통")
    p.add_argument(
        "--query",
        help='Gmail 검색 문법 (예: "from:snu.ac.kr 안내"). 지정하면 --days 대신 이걸 쓴다.',
    )
    p.set_defaults(func=cmd_headers)

    p = sub.add_parser("raw", help="특정 메일의 전체 헤더 + MIME 구조")
    p.add_argument("uid")
    p.add_argument("--folder", default="INBOX")
    p.set_defaults(func=cmd_raw)

    p = sub.add_parser("csv", help="대량메일만 골라 CSV 로 저장 (개인정보 스크러빙 포함)")
    p.add_argument("--folder", default="INBOX")
    p.add_argument("--count", type=int, default=30, help="CSV 에 담을 최신 N건")
    p.add_argument("--scan", type=int, default=400, help="검색 후보를 최근 N개로 제한")
    p.add_argument("--out", help="출력 경로 (기본 out/bulkmail.csv)")
    p.set_defaults(func=cmd_csv)

    p = sub.add_parser("dump", help="특정 메일의 본문 HTML / 이미지를 out/ 에 저장")
    p.add_argument("uid")
    p.add_argument("--folder", default="INBOX")
    p.set_defaults(func=cmd_dump)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
