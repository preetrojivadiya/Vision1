package com.example.vision1.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DocumentCollection implements StorageItem {
    private String id;
    private String name;
    private List<StoredDocument> documents;

    public DocumentCollection(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.documents = new ArrayList<>();
    }

    // Constructor used when loading from SharedPreferences
    public DocumentCollection(String id, String name) {
        this.id = id;
        this.name = name;
        this.documents = new ArrayList<>();
    }

    public String getId() { return id; }
    public void setName(String name) { this.name = name; }
    public List<StoredDocument> getDocuments() { return documents; }

    public void addDocument(StoredDocument document) {
        if (!documents.contains(document)) {
            documents.add(document);
        }
    }

    public void removeDocument(StoredDocument document) {
        documents.remove(document);
    }

    @Override
    public int getItemType() { return TYPE_COLLECTION; }

    @Override
    public String getName() { return name; }
}