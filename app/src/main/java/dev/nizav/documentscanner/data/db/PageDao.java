package dev.nizav.documentscanner.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface PageDao {
    @Insert
    long insert(PageEntity page);

    @Update
    void update(PageEntity page);

    @Query("SELECT * FROM pages WHERE id = :id LIMIT 1")
    PageEntity get(long id);

    @Query(
            "SELECT * FROM pages WHERE projectId = :projectId " +
            "ORDER BY position ASC"
    )
    List<PageEntity> listForProject(long projectId);

    @Query(
            "SELECT COALESCE(MAX(position), -1) FROM pages " +
            "WHERE projectId = :projectId"
    )
    int maxPosition(long projectId);

    @Query("DELETE FROM pages WHERE id = :id")
    void delete(long id);

    @Query(
            "UPDATE pages SET position = :position, updatedAt = :updatedAt " +
            "WHERE id = :id"
    )
    void setPosition(long id, int position, long updatedAt);

    @Query(
            "UPDATE pages SET ocrText = :text, ocrDataJson = :dataJson, " +
            "ocrUpdatedAt = :updatedAt, updatedAt = :updatedAt WHERE id = :pageId"
    )
    void updateOcr(long pageId, String text, String dataJson, long updatedAt);
}
