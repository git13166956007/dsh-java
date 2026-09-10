package io.github.git13166956007.dsh.model;

/** A model exposed by a provider's model catalog endpoint. */
public record ModelCatalogEntry(String id, String object, Long created, String ownedBy) {
}
