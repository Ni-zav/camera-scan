package dev.nizav.documentscanner.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface ProjectDao {
    @Insert
    long insert(ProjectEntity project);

    @Update
    void update(ProjectEntity project);

    @Query("SELECT * FROM projects WHERE id = :id LIMIT 1")
    ProjectEntity get(long id);

    @Query(
            "SELECT p.*, " +
            "(SELECT COUNT(*) FROM pages pg WHERE pg.projectId = p.id) AS pageCount, " +
            "(SELECT filePath FROM pages pg WHERE pg.projectId = p.id " +
            " ORDER BY position ASC LIMIT 1) AS coverPath " +
            "FROM projects p ORDER BY p.updatedAt DESC"
    )
    List<ProjectRow> listRows();

    @Query("DELETE FROM projects WHERE id = :id")
    void delete(long id);
}
