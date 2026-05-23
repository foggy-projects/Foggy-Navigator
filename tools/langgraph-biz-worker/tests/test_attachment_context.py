"""Tests for attachment context and evidence safety."""

from __future__ import annotations

from langgraph_biz_worker.runtime.attachment_context import build_attachment_evidence


def test_attachment_evidence_uses_digest_without_raw_signed_url():
    evidence = build_attachment_evidence([
        {
            "id": "att-1",
            "name": "pod-photo.png",
            "mimeType": "image/png",
            "provider": "tms",
            "url": "https://tms.example.com/files/pod-photo.png?token=secret&signature=abc",
        }
    ])

    assert evidence["attachment_count"] == 1
    assert evidence["attachment_ids"] == ["att-1"]
    assert evidence["attachment_names"] == ["pod-photo.png"]
    assert evidence["attachment_media_types"] == ["image/png"]
    assert evidence["attachment_ref_types"] == ["id", "name", "media_type", "provider", "url_digest"]
    assert evidence["attachment_url_digests"][0].startswith("sha256:")
    assert "token=secret" not in str(evidence)
    assert "signature=abc" not in str(evidence)
    assert "https://tms.example.com/files/pod-photo.png" not in str(evidence)


def test_attachment_evidence_reports_zero_for_empty_input():
    assert build_attachment_evidence(None) == {"attachment_count": 0}
