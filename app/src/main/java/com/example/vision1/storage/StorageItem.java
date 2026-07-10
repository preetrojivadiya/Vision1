package com.example.vision1.storage;

public interface StorageItem {
    int TYPE_DOCUMENT = 0;
    int TYPE_COLLECTION = 1;

    int getItemType();
    String getName();
}