package io.github.rs0325.mcicon;

/**
 * 公式manifestの正式版と、同梱対応表による描画の受入状態。
 * @param id 正式版のexact ID
 * @param releaseTime 公式の公開日時（ISO-8601）
 * @param support verified／experimental／discovered／unsupported。新しい未登録版はdiscoveredとなる
 * @param metadataUrl 当該版の公式version JSONのURL
 * @param metadataSha1 公式manifestが指定するversion JSONのSHA-1
 */
public record VersionInfo(String id, String releaseTime, String support, String metadataUrl, String metadataSha1) {}
