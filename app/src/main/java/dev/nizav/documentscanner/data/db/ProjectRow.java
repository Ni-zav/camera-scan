package dev.nizav.documentscanner.data.db;

import androidx.room.ColumnInfo;
import androidx.room.Embedded;

public final class ProjectRow {
    @Embedded
    public ProjectEntity project;

    @ColumnInfo(name = "pageCount")
    public int pageCount;

    @ColumnInfo(name = "coverPath")
    public String coverPath;
}
