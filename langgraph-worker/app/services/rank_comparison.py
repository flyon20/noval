"""Calendar-scoped rank facts, computed only from complete matching snapshots."""
from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
import re
from typing import Any, TypedDict

from app.models.knowledge import RankLookupResult


class RankComparisonPeriod(TypedDict):
    label: str
    startDate: str
    endDate: str


def requested_rank_periods(question: str, context: str, today: date) -> list[RankComparisonPeriod]:
    text = (question or "").lower()
    if not any(word in text for word in ("比较", "对比", "相比", "变化", "升降", "留存")):
        return []
    if not any(word in (text + context.lower()) for word in ("榜", "排名", "top")):
        return []
    current_start = today - timedelta(days=today.weekday())
    start, end = current_start - timedelta(days=7), current_start - timedelta(days=1)
    label = "上周"
    explicit = re.search(
        r"(\d{4})[年/-](\d{1,2})[月/-](\d{1,2})日?\s*(?:至|到|[~～—–])\s*"
        r"(?:(\d{4})[年/-])?(\d{1,2})[月/-](\d{1,2})日?", text,
    )
    if explicit:
        if not any(word in text for word in ("本周", "这周", "本期", "当前", "刚才")):
            return []
        try:
            year, month, day, end_year, end_month, end_day = explicit.groups()
            start = date(int(year), int(month), int(day))
            end = date(int(end_year or year), int(end_month), int(end_day))
        except ValueError:
            return []
        if end < start or (end - start).days > 365:
            return []
        label = "指定时段"
    elif not any(word in text for word in ("上周", "上星期", "上个星期", "往期", "历史榜")):
        return []
    return [
        {"label": label, "startDate": start.isoformat(), "endDate": end.isoformat()},
        {"label": "本周", "startDate": current_start.isoformat(), "endDate": today.isoformat()},
    ]


def _stamp(row: RankLookupResult) -> datetime | None:
    try:
        value = datetime.fromisoformat(str(row.snapshotTime).replace("Z", "+00:00"))
        return value.replace(tzinfo=timezone.utc) if value.tzinfo is None else value
    except (TypeError, ValueError):
        return None


def _board(row: RankLookupResult) -> tuple[str | None, ...]:
    return (row.platform, row.channelCode or row.channelName, row.boardCode or row.boardName, row.category)


def build_rank_comparison(
    periods: list[RankComparisonPeriod], batches: list[list[RankLookupResult]], *, top_n: int,
    failed_periods: set[int] | None = None,
) -> tuple[str, list[RankLookupResult], dict[str, Any]]:
    if len(periods) != 2 or len(batches) != 2 or not 1 <= top_n <= 100:
        raise ValueError("a rank comparison requires two periods and a bounded Top N")
    selected: list[list[RankLookupResult]] = []
    sources: list[RankLookupResult] = []
    metadata: list[dict[str, Any]] = []
    for index, (period, rows) in enumerate(zip(periods, batches)):
        start, end = date.fromisoformat(period["startDate"]), date.fromisoformat(period["endDate"])
        if start > end or (end - start).days > 365:
            raise ValueError("invalid comparison period")
        valid = [row for row in rows if _stamp(row) is not None and start <= date.fromisoformat(str(row.snapshotTime)[:10]) <= end]
        issue = "query_failed" if index in (failed_periods or set()) else None
        if not valid and issue is None:
            issue = "missing_snapshot" if not rows else "invalid_snapshot_scope"
        boards = {_board(row) for row in valid}
        if len(boards) > 1:
            issue = "ambiguous_board"
        latest = max(valid, key=lambda row: (_stamp(row), str(row.snapshotId))) if valid else None
        group = [row for row in valid if latest is not None and row.snapshotId == latest.snapshotId
                 and row.snapshotTime == latest.snapshotTime and _board(row) == _board(latest)
                 and 1 <= (row.rankNo or 0) <= top_n]
        by_identity = {str(row.bookId): row for row in group if row.bookId is not None}
        complete = (len(by_identity) == top_n and {row.rankNo for row in group} == set(range(1, top_n + 1))
                    and len({(str(row.bookId), row.rankNo) for row in group}) == top_n)
        if issue is None and not complete:
            issue = "incomplete_top_n"
        group = sorted(by_identity.values(), key=lambda row: row.rankNo or 0)
        selected.append(group)
        metadata.append({**period, "snapshotId": latest.snapshotId if latest else None,
                         "snapshotTime": latest.snapshotTime if latest else None, "rowCount": len(group),
                         "complete": complete and issue is None, "issue": issue,
                         "firstCitation": len(sources) + 1 if group else None})
        sources.extend(group)
    same_board = bool(selected[0] and selected[1] and all(_board(selected[0][0])[:3])
                      and _board(selected[0][0]) == _board(selected[1][0]))
    supported = all(item["complete"] for item in metadata) and same_board
    before = {str(row.bookId): (row, index + 1) for index, row in enumerate(selected[0])}
    after = {str(row.bookId): (row, len(selected[0]) + index + 1) for index, row in enumerate(selected[1])}
    changes, entered, left = [], [], []
    if supported:
        for identity, (row, citation) in after.items():
            if identity in before:
                old, old_citation = before[identity]
                changes.append({"bookId": row.bookId, "bookName": row.bookName, "previousBookName": old.bookName,
                                "previousRank": old.rankNo, "currentRank": row.rankNo,
                                "delta": old.rankNo - row.rankNo, "previousCitation": old_citation, "currentCitation": citation})
            else:
                entered.append({"bookId": row.bookId, "bookName": row.bookName, "rankNo": row.rankNo, "citation": citation})
        left = [{"bookId": row.bookId, "bookName": row.bookName, "rankNo": row.rankNo, "citation": citation}
                for identity, (row, citation) in before.items() if identity not in after]
    result = {"topN": top_n, "periods": metadata, "comparisonSupported": supported,
              "sameBoard": same_board, "commonCount": len(changes) if supported else None,
              "retentionRate": len(changes) / top_n if supported else None,
              "rankChanges": changes, "enteredTopN": entered, "leftTopN": left}
    evidence_range = f"[1]–[{len(sources)}]" if sources else ""
    lines = [f"Top{top_n} 榜单比较：按作品 ID 对应，每个时段取最后一份已存快照{evidence_range}。", ""]
    for period in metadata:
        scope = f"{period['label']}（{period['startDate']} 至 {period['endDate']}）"
        if period["issue"] == "missing_snapshot":
            lines.append(f"- {scope}：查询成功，但该时段没有存档。")
        elif period["issue"] == "query_failed":
            lines.append(f"- {scope}：查询失败，暂时无法确认是否存在存档。")
        elif period["issue"] in {"ambiguous_board", "invalid_snapshot_scope"}:
            lines.append(f"- {scope}：返回数据的榜单或快照范围无法确认。")
        else:
            citation = f"[{period['firstCitation']}]" if period["firstCitation"] else ""
            lines.append(f"- {scope}：快照 {period['snapshotTime']}，覆盖 {period['rowCount']}/{top_n} 名{citation}。")
    if supported:
        lines += ["", "| 共同作品 | 前次排名 | 本次排名 | 变化 |", "| --- | ---: | ---: | --- |"]
        for item in changes:
            name = item["bookName"] or str(item["bookId"])
            if item["previousBookName"] != item["bookName"]:
                name = f"{item['previousBookName']} → {name}（ID {item['bookId']}）"
            name = name.replace("|", "\\|").replace("\n", " ")
            delta = item["delta"]
            change = f"↑{delta}" if delta > 0 else f"↓{-delta}" if delta < 0 else "持平"
            lines.append(f"| {name} | {item['previousRank']} | {item['currentRank']} | {change}[{item['previousCitation']}][{item['currentCitation']}] |")
        for label, items in (("新进入", entered), ("离开", left)):
            names = "、".join(f"《{item['bookName']}》（第{item['rankNo']}名）[{item['citation']}]" for item in items) or f"无{evidence_range}"
            lines += ["", f"{label} Top{top_n}：{names}。"]
        lines += ["", f"留存比例：{len(changes)}/{top_n} = {len(changes) / top_n:.0%}{evidence_range}。"]
    else:
        lines += ["", "两期完整且属于同一榜单的证据尚不齐全，不能计算升降、新进/离开或留存比例。"]
        for period, group in zip(metadata, selected):
            if period["complete"]:
                start = period["firstCitation"]
                names = "、".join(f"{row.rankNo}《{row.bookName}》[{start + index}]" for index, row in enumerate(group))
                lines += ["", f"{period['snapshotTime']} 的 Top{top_n}：{names}。"]
    lines += ["", f"范围仅为该榜单 Top{top_n}；离开前列不代表离开整个榜单，也不能据此推断持续趋势{evidence_range}。"]
    return "\n".join(lines), sources, result
