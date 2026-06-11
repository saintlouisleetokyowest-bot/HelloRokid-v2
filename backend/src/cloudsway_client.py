"""Cloudsway SmartSearch API client."""

from __future__ import annotations

import logging
from typing import Any
from urllib.parse import urlparse

import requests

logger = logging.getLogger(__name__)


class CloudswayClient:
    def __init__(self, base_path: str, endpoint: str, access_key: str) -> None:
        self.base_path = base_path.strip().rstrip("/")
        self.endpoint = endpoint.strip()
        self.access_key = access_key.strip()
        self.search_url = f"https://{self.base_path}/search/{self.endpoint}/smart"

    @property
    def configured(self) -> bool:
        return bool(self.base_path and self.endpoint and self.access_key)

    def search(
        self,
        query: str,
        *,
        count: int = 8,
        main_text: bool = True,
        content_timeout: float = 3.0,
    ) -> list[dict[str, Any]]:
        if not query.strip():
            return []

        params: dict[str, Any] = {
            "q": query.strip(),
            "count": max(1, min(count, 10)),
            "enableContent": "true",
            "contentType": "TEXT",
            "contentTimeout": content_timeout,
            "mainText": "true" if main_text else "false",
        }
        headers = {
            "Authorization": f"Bearer {self.access_key}",
            "pragma": "no-cache",
        }

        response = requests.get(
            self.search_url,
            headers=headers,
            params=params,
            timeout=30,
        )
        if not response.ok:
            detail = response.text[:500]
            raise ValueError(
                f"Cloudsway 搜索失败 HTTP {response.status_code}: {detail}"
            )

        payload = response.json()
        pages = payload.get("webPages", {}).get("value", [])
        if not isinstance(pages, list):
            return []
        return [page for page in pages if isinstance(page, dict)]


def build_search_queries(contact: dict[str, str]) -> list[str]:
    """Build a small set of search queries from extracted contact info."""
    company = contact.get("company", "").strip()
    website = contact.get("website", "").strip()
    name = contact.get("name", "").strip()
    title = contact.get("title", "").strip()

    queries: list[str] = []
    if company:
        queries.append(
            f'"{company}" company profile industry business revenue employees partners'
        )
        queries.append(f'"{company}" 公司 业务 行业 规模 融资 合作伙伴 最新动态')
    elif website:
        domain = extract_domain(website)
        if domain:
            queries.append(f'site:{domain} company about business products')

    if not queries and name and title:
        queries.append(f'"{name}" "{title}" company profile')

    # Deduplicate while preserving order.
    seen: set[str] = set()
    unique: list[str] = []
    for query in queries:
        if query not in seen:
            seen.add(query)
            unique.append(query)
    return unique[:2]


def extract_domain(website: str) -> str:
    value = website.strip()
    if not value:
        return ""
    if "://" not in value:
        value = f"https://{value}"
    try:
        host = urlparse(value).netloc or urlparse(value).path
        return host.lower().removeprefix("www.")
    except Exception:
        return ""


def merge_search_pages(pages_list: list[list[dict[str, Any]]]) -> list[dict[str, Any]]:
    merged: list[dict[str, Any]] = []
    seen_urls: set[str] = set()
    for pages in pages_list:
        for page in pages:
            url = str(page.get("url", "")).strip()
            if url and url in seen_urls:
                continue
            if url:
                seen_urls.add(url)
            merged.append(page)
    return merged[:10]


def format_search_results_for_prompt(pages: list[dict[str, Any]]) -> str:
    if not pages:
        return "（未检索到相关网页结果）"

    blocks: list[str] = []
    for index, page in enumerate(pages, start=1):
        title = str(page.get("name", "")).strip()
        url = str(page.get("url", "")).strip()
        snippet = str(page.get("snippet", "")).strip()
        main_text = str(page.get("mainText", "")).strip()
        site_name = str(page.get("siteName", "")).strip()
        published = str(page.get("datePublished", "")).strip()

        lines = [f"[{index}] {title or 'Untitled'}"]
        if site_name:
            lines.append(f"Site: {site_name}")
        if url:
            lines.append(f"URL: {url}")
        if published:
            lines.append(f"Published: {published}")
        if snippet:
            lines.append(f"Snippet: {snippet[:500]}")
        if main_text:
            lines.append(f"MainText: {main_text[:1000]}")
        blocks.append("\n".join(lines))
    return "\n\n".join(blocks)
