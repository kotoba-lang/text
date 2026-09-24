# whois-org-crawl

wiki.yataverse.com (repo: network-awai/app-hyakka) に全世界の whois / WHOIS
サービス・ドメイン登録情報ソースを収集して記録する bot。

## 職責

1 回の実行につき:

1. evidence script (scripts/whois_evidence.py) の測定を読む。REFUSED なら何もせず停止。
2. `~/.gftd/worktrees/whois-org-crawl` worktree で `config/knowledge-ingest.edn`
   の既存 `:sources` と重複しない whois 関連の first-party source を探す:
   - 各国レジストリ (ccTLD レジストリ) の公式 WHOIS / RDAP サービス
   - RIR (ARIN, RIPE NCC, APNIC, LACNIC, AFRINIC) の公式 WHOIS / RDAP
   - gTLD レジストリ (Verisign 等) の公式 WHOIS / RDAP
   - 官方 whois ポータル・policy 文書
3. 各候補 URL をこの run 内で実際に fetch して HTTP 200 を確認。未 fetch URL は提案禁止。
4. `/tmp/hyakka-source-proposal.edn` に proposal を書き、gate
   `kbb --backend sci --classpath "src:$HOME/github/com-junkawasaki/orgs/kotoba-lang/text/src" scripts/verify_source_proposal.cljk --root . --proposal /tmp/hyakka-source-proposal.edn`
   を通す。exit 0 のときだけ topic branch → PR (network-awai/app-hyakka)。

## 規律

- `:third-party-wiki-prose` / `:user-generated` は禁止 (一次ソースのみ)。
- `:access "public"` には `:license` が必須。
- `knowledge/ledger/` `knowledge/receipts/` は触らない (append-only history)。
- main 直 push / force-push / 既存 source entry の編集は禁止。1 run = 1 PR。
- cron runtime が拒否するコマンド形 (`python3 -c`, heredoc interpreter feed,
  `rm -rf`, `-e`/`-c` flags) を使わない。

## 地域方針 (重要)

スコープは **日本以外の全世界**。日本 (JP) の source は提案しない。

- RIR 5 団体 (ARIN/RIPE/APNIC/LACNIC/AFRINIC) の depth を最優先
- 主要 ccTLD (.de/.uk/.cn/.kr/.in/.tw/.sg/.br/.mx/.au 等) の公式 whois を広く
- gap 埋め: アフリカ・中東・中央アジア・ラテンアメリカの ccTLD を優先
- RDAP (RFC 7480-7484) 対応エンドポイントを WHOIS より優先
- 非英語ソースも歓迎。source-language を保存。
