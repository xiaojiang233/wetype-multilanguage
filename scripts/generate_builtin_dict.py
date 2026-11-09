#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generates the built-in Japanese dictionaries for Rime in wetype-multilanguage:
    common/src/main/resources/rime/japanese.kana.dict.yaml   (kana table, with x/l aliases for the small kana)
    common/src/main/resources/rime/japanese.core.dict.yaml   (words)

Sources (BSD-3-Clause / Public Domain), downloaded once into build/rime-src/ (override with --src) and reused:
 1. gkovacs/rime-japanese: japanese.kana.dict.yaml
 2. gkovacs/rime-japanese: japanese.mozc.dict.yaml, converted from the Google Mozc dictionary

Why the core dictionary is small
--------------------------------
librime compiles the dictionary on the phone (once per install or update, then it is cached). The dictionary codes are whole
words, so the speller algebra in japanese.schema.yaml runs once per distinct code, and a boost regex runs per rule and
spelling. 320,000 entries took 41 s on the emulator; the sources hold 1.13 million lines, so the cut has to be chosen, not
just taken from the top. What is kept:
 - every entry that has a corpus frequency in the source (about 57,000). These are everyday words with a real ranking,
 - every single kanji, so any character can be typed from one of its readings (weighted below all of the above),
 - COMMON_PHRASES below, which win over the source weights.
About a million entries without a frequency (inflected and compound fragments such as "word + particle") are dropped.
Entries whose code Rime could never match (digits, underscores) are dropped too, and so is anything the kana table defines.

The source weights a kanji by how common the character is, the same for every reading. So an everyday kanji gets a large
weight for its rarest readings as well (日 for "ka", as in 二日), and typing the particle "ka" at the end of a sentence would turn
into 日. Readings that are a single syllable (the codes of the kana table) are therefore capped below the kana's own weight,
so a lone syllable shows the kana first, and longer readings keep their weight.
"""

import argparse
import os
import re
import sys
import urllib.request

BASE_URL = 'https://raw.githubusercontent.com/gkovacs/rime-japanese/master/'
KANA_NAME = 'japanese.kana.dict.yaml'
MOZC_NAME = 'japanese.mozc.dict.yaml'

KANA_WEIGHT = 80000
# Below every weighted entry in the source (the lowest is 36), so a kanji only shows after real words.
UNWEIGHTED_KANJI_WEIGHT = 20
# Source entries lighter than this are dropped. 0 keeps all of them.
MIN_WEIGHT = 0
KANJI_CAP = 40000

# Kana the upstream table does not have. The long vowel mark is typed with the hyphen key (and the keyboard's own key), and the
# speller alphabet has the hyphen, so "ka-" reads か + ー and a lone "-" gives ー as a candidate.
KANA_EXTRA = [
    ('ー', '-'),
]

KANJI_RE = re.compile('[一-鿿]')
# What the speller alphabet can type: lowercase letters, and the hyphen for the long vowel mark.
CODE_RE = re.compile(r'^[a-z][a-z-]*$')

COMMON_PHRASES = [
    # Top greetings and daily phrases (boosted so sentence synthesis or kanji fragments never break them)
    ('こんにちは', 'konnichiha', 300000),
    ('こんにちは', 'konnnichiha', 300000),
    ('こんにちは', 'konnichiwa', 300000),
    ('こんにちは', 'konnnichiwa', 300000),
    ('こんにちわ', 'konnichiwa', 50000),
    ('こんにちわ', 'konnnichiwa', 50000),
    ('こんばんは', 'konbanha', 300000),
    ('こんばんは', 'konnnbanha', 300000),
    ('こんばんは', 'konbanwa', 300000),
    ('こんばんは', 'konnnbanwa', 300000),
    ('おはようございます', 'ohayougozaimasu', 300000),
    ('おはよう', 'ohayou', 250000),
    ('さようなら', 'sayounara', 200000),
    ('ありがとうございます', 'arigatougozaimasu', 300000),
    ('ありがとうございました', 'arigatougozaimashita', 280000),
    ('ありがとう', 'arigatou', 250000),
    ('有難う', 'arigatou', 100000),
    ('お疲れ様です', 'otsukaresamadesu', 300000),
    ('お疲れ様でした', 'otsukaresamadeshita', 280000),
    ('お疲れ様', 'otsukaresama', 250000),
    ('よろしくお願いします', 'yoroshikuonegaishimasu', 300000),
    ('よろしくお願い申し上げます', 'yoroshikuonegaimoushiagemasu', 250000),
    ('よろしく', 'yoroshiku', 220000),
    ('すみません', 'sumimasen', 300000),
    ('ごめんなさい', 'gomennasai', 250000),
    ('申し訳ありません', 'moushiwakearimasen', 250000),
    ('申し訳ございません', 'moushiwakegozaimasen', 250000),
    ('初めまして', 'hajimemashite', 250000),
    ('はじめまして', 'hajimemashite', 250000),
    ('いただきます', 'itadakimasu', 250000),
    ('ごちそうさまでした', 'gochisousamadeshita', 250000),
    ('どういたしまして', 'douitashimashite', 250000),
    ('どうもありがとうございます', 'doumoarigatougozaimasu', 250000),
    ('どうも', 'doumo', 180000),
    ('どうぞ', 'douzo', 180000),
    ('了解です', 'ryoukaidesu', 250000),
    ('了解しました', 'ryoukaishimashita', 250000),
    ('了解', 'ryoukai', 220000),
    ('分かりました', 'wakarimashita', 250000),
    ('わかりました', 'wakarimashita', 250000),
    ('分かった', 'wakatta', 220000),
    ('わかった', 'wakatta', 200000),
    ('大丈夫です', 'daijoubudesu', 250000),
    ('大丈夫', 'daijoubu', 250000),
    ('だいじょうぶ', 'daijoubu', 200000),
    ('なるほど', 'naruhodo', 220000),
    ('確かに', 'tashikani', 220000),
    ('たしかに', 'tashikani', 200000),
    ('そうですね', 'soudesune', 250000),
    ('そうです', 'soudesu', 220000),
    ('はい', 'hai', 200000),
    ('いいえ', 'iie', 180000),
    ('私', 'watashi', 200000),
    ('私', 'watasi', 200000),
    ('わたし', 'watashi', 180000),
    ('わたし', 'watasi', 180000),
    ('僕', 'boku', 180000),
    ('今日', 'kyou', 200000),
    ('明日', 'ashita', 200000),
    ('昨日', 'kinou', 200000),
    ('日本', 'nihon', 200000),
    ('日本', 'nippon', 180000),
    ('日本語', 'nihongo', 200000),
    ('先生', 'sensei', 150000),
    ('本当', 'hontou', 180000),
    ('本当に', 'hontouni', 200000),
    ('ほんとう', 'hontou', 150000),
    ('すごい', 'sugoi', 180000),
    ('凄い', 'sugoi', 150000),
    ('すごく', 'sugoku', 150000),
    ('とても', 'totemo', 180000),
    ('可愛い', 'kawaii', 180000),
    ('かわいい', 'kawaii', 180000),
    ('嬉しい', 'ureshii', 180000),
    ('うれしい', 'ureshii', 150000),
    ('楽しい', 'tanoshii', 180000),
    ('たのしい', 'tanoshii', 150000),
    ('美味しい', 'oishii', 180000),
    ('おいしい', 'oishii', 150000),
    ('美味しかった', 'oishikatta', 150000),
    ('よかった', 'yokatta', 180000),
    ('良かった', 'yokatta', 150000),
    ('待って', 'matte', 180000),
    ('待った', 'matta', 120000),
    ('行って', 'itte', 180000),
    ('行った', 'itta', 120000),
    ('行きます', 'ikimasu', 150000),
    ('行きました', 'ikimashita', 120000),
    ('食べて', 'tabete', 150000),
    ('食べた', 'tabeta', 120000),
    ('食べます', 'tabemasu', 120000),
    ('飲んで', 'nonde', 150000),
    ('飲んだ', 'nonda', 120000),
    ('見て', 'mite', 180000),
    ('見た', 'mita', 120000),
    ('見ます', 'mimasu', 120000),
    ('来て', 'kite', 180000),
    ('来た', 'kita', 120000),
    ('来ます', 'kimasu', 120000),
    ('して', 'shite', 180000),
    ('した', 'shita', 120000),
    ('します', 'shimasu', 150000),
    ('しました', 'shimashita', 150000),
    ('知って', 'shitte', 120000),
    ('知った', 'shitta', 100000),
    ('思って', 'omotte', 150000),
    ('思った', 'omotta', 120000),
    ('言って', 'itte', 150000),
    ('言った', 'itta', 120000),
    ('見ます', 'mimasu', 30000),
    ('来て', 'kite', 40000),
    ('来た', 'kita', 35000),
    ('来ます', 'kimasu', 30000),
    ('して', 'shite', 40000),
    ('した', 'shita', 35000),
    ('します', 'shimasu', 35000),
    ('しました', 'shimashita', 35000),
    ('知って', 'shitte', 30000),
    ('知った', 'shitta', 25000),
    ('思って', 'omotte', 35000),
    ('思った', 'omotta', 30000),
    ('言って', 'itte', 35000),
    ('言った', 'itta', 30000),
    ('分かった', 'wakatta', 35000),
    ('分かりました', 'wakarimashita', 35000),
    ('わかりました', 'wakarimashita', 30000),
]

# Grammar chunks: endings and particles that follow a word. The source has few of them, and without them a sentence is cut
# into whatever words happen to match ("shiteimasu" became 指定 + ます instead of しています).
GRAMMAR_CHUNKS = [
    ('しています', 'shiteimasu', 60000),
    ('していました', 'shiteimashita', 50000),
    ('していません', 'shiteimasen', 50000),
    ('している', 'shiteiru', 60000),
    ('していた', 'shiteita', 50000),
    ('していない', 'shiteinai', 50000),
    ('ています', 'teimasu', 60000),
    ('ていました', 'teimashita', 50000),
    ('ていません', 'teimasen', 50000),
    ('ている', 'teiru', 60000),
    ('ていた', 'teita', 50000),
    ('てください', 'tekudasai', 60000),
    ('ください', 'kudasai', 60000),
    ('下さい', 'kudasai', 30000),
    ('ですか', 'desuka', 60000),
    ('ですね', 'desune', 50000),
    ('ですよ', 'desuyo', 50000),
    ('でした', 'deshita', 60000),
    ('でしょう', 'deshou', 50000),
    ('でしょうか', 'deshouka', 40000),
    ('ますか', 'masuka', 60000),
    ('ますね', 'masune', 40000),
    ('ません', 'masen', 60000),
    ('ませんか', 'masenka', 50000),
    ('ましょう', 'mashou', 50000),
    ('ました', 'mashita', 60000),
    ('ます', 'masu', 60000),
    ('ありません', 'arimasen', 60000),
    ('ありました', 'arimashita', 50000),
    ('あります', 'arimasu', 60000),
    ('います', 'imasu', 60000),
    ('いません', 'imasen', 50000),
    ('いました', 'imashita', 50000),
    ('なります', 'narimasu', 50000),
    ('なりました', 'narimashita', 50000),
    ('できます', 'dekimasu', 50000),
    ('できました', 'dekimashita', 40000),
    ('できる', 'dekiru', 50000),
    ('できない', 'dekinai', 50000),
    ('じゃない', 'janai', 50000),
    ('ではありません', 'dehaarimasen', 40000),
    ('だけど', 'dakedo', 40000),
    ('でも', 'demo', 50000),
    ('かもしれない', 'kamoshirenai', 50000),
    ('と思います', 'toomoimasu', 40000),
    ('と思う', 'toomou', 40000),
    ('になります', 'ninarimasu', 40000),
    ('について', 'nitsuite', 50000),
    ('によって', 'niyotte', 40000),
    ('しない', 'shinai', 50000),
    ('しません', 'shimasen', 50000),
    ('したい', 'shitai', 50000),
    ('たい', 'tai', 30000),
    ('たくない', 'takunai', 40000),
    ('てくださいます', 'tekudasaimasu', 30000),
    ('お願いします', 'onegaishimasu', 50000),
    ('お願い', 'onegai', 40000),
    ('これから', 'korekara', 40000),
    ('それでは', 'soredeha', 40000),
    ('そうです', 'soudesu', 50000),
    ('そうですね', 'soudesune', 40000),
    ('今日は', 'kyouha', 40000),
    ('今日の', 'kyouno', 30000),
    ('明日は', 'ashitaha', 30000),
]


def read_rows(path):
    """Yield (surface, code, weight or None) from the body of a Rime dictionary file (everything after the '...' line)."""
    in_body = False
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.rstrip('\n')
            if not in_body:
                if line == '...':
                    in_body = True
                continue
            if not line or line.startswith('#'):
                continue
            parts = line.split('\t')
            if len(parts) < 2 or not parts[0] or not parts[1]:
                continue
            weight = int(parts[2]) if len(parts) >= 3 and parts[2].strip().isdigit() else None
            yield parts[0], parts[1], weight


def fetch(src_dir, name):
    path = os.path.join(src_dir, name)
    if not os.path.exists(path):
        os.makedirs(src_dir, exist_ok=True)
        print(f'   downloading {name} ...')
        urllib.request.urlretrieve(BASE_URL + name, path)
    return path


def small_kana_aliases(code):
    """The source writes small kana with a leading underscore (_a, _tsu). IMEs type them as xa/la, xtsu/ltsu, xtu/ltu."""
    if not code.startswith('_'):
        return []
    rest = code[1:]
    out = ['x' + rest, 'l' + rest]
    if rest == 'tsu':
        out += ['xtu', 'ltu']
    return out


def build_kana(src_dir):
    seen = set()
    rows = []
    for surface, code, _ in read_rows(fetch(src_dir, KANA_NAME)):
        for c in [code] + small_kana_aliases(code):
            if (surface, c) not in seen:
                seen.add((surface, c))
                rows.append((surface, c))
    for row in KANA_EXTRA:
        if row not in seen:
            seen.add(row)
            rows.append(row)
    return rows


def build_core(src_dir, kana_pairs):
    kana_codes = {code for _, code in kana_pairs}
    weighted = {}
    kanji = {}
    capped = 0
    for surface, code, weight in read_rows(fetch(src_dir, MOZC_NAME)):
        if not CODE_RE.match(code):
            continue
        key = (surface, code)
        is_kanji = len(surface) == 1 and KANJI_RE.match(surface) is not None
        if weight is not None:
            if is_kanji and weight > KANJI_CAP:
                weight = KANJI_CAP
                capped += 1
            if weight >= MIN_WEIGHT and weight > weighted.get(key, 0):
                weighted[key] = weight
        elif is_kanji:
            kanji.setdefault(key, UNWEIGHTED_KANJI_WEIGHT)

    entries = dict(kanji)
    entries.update(weighted)
    for surface, code, weight in COMMON_PHRASES + GRAMMAR_CHUNKS:
        entries[(surface, code)] = weight
    for key in kana_pairs:
        entries.pop(key, None)
    print(f'   weighted source entries: {len(weighted)} ({capped} single-kanji readings capped at {KANJI_CAP}), '
          f'single-kanji readings added: {len(kanji)}, phrases: {len(COMMON_PHRASES)}, grammar chunks: {len(GRAMMAR_CHUNKS)} '
          f'-> {len(entries)} entries, {len({c for _, c in entries})} distinct codes')
    return sorted(entries.items(), key=lambda kv: (-kv[1], kv[0][0], kv[0][1]))


def write_dict(path, header, rows):
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(header)
        f.writelines(rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n\n')[0])
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    parser.add_argument('--src', default=os.path.join(root, 'build', 'rime-src'),
                        help='directory holding (or receiving) the downloaded upstream dictionaries')
    parser.add_argument('--out', default=os.path.join(root, 'common', 'src', 'main', 'resources', 'rime'))
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)

    print('1. japanese.kana.dict.yaml ...')
    kana = build_kana(args.src)
    kana_file = os.path.join(args.out, 'japanese.kana.dict.yaml')
    write_dict(
        kana_file,
        '# Rime dictionary\n# Kana table from gkovacs/rime-japanese (BSD-3-Clause / Public Domain); see scripts/generate_builtin_dict.py\n'
        '# encoding: utf-8\n---\nname: japanese.kana\nversion: "1.1"\nsort: by_weight\nuse_preset_vocabulary: false\n...\n',
        [f'{s}\t{c}\t{KANA_WEIGHT}\n' for s, c in kana])
    print(f'   saved {len(kana)} kana entries to {kana_file}')

    print('2. japanese.core.dict.yaml ...')
    core = build_core(args.src, set(kana))
    core_file = os.path.join(args.out, 'japanese.core.dict.yaml')
    write_dict(
        core_file,
        '# Rime dictionary\n# Converted from the Google Mozc dictionary by gkovacs/rime-japanese (BSD-3-Clause / Public Domain)\n'
        '# Selection and weights: scripts/generate_builtin_dict.py\n'
        '# encoding: utf-8\n---\nname: japanese.core\nversion: "1.1"\nsort: by_weight\nuse_preset_vocabulary: false\n...\n',
        [f'{s}\t{c}\t{w}\n' for (s, c), w in core])
    print(f'   saved {len(core)} entries to {core_file} ({os.path.getsize(core_file) / 1024:.0f} KiB)')


if __name__ == '__main__':
    sys.exit(main())
