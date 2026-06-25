package com.example.vision1;

public interface StorageItem {
    int TYPE_DOCUMENT = 0;
    int TYPE_COLLECTION = 1;

    int getItemType();
    String getName();
}