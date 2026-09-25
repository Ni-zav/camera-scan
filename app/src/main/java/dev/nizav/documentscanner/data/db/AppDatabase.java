package dev.nizav.documentscanner.data.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {ProjectEntity.class, PageEntity.class},
        version = 1,
        exportSchema = false
)
public abstract class AppDatabase extends RoomDatabase {
    private static volatile AppDatabase instance;

    public abstract ProjectDao projectDao();
    public abstract PageDao pageDao();

    public static AppDatabase get(Context context) {
        AppDatabase local = instance;
        if (local != null) {
            return local;
        }

        synchronized (AppDatabase.class) {
            local = instance;
            if (local == null) {
                local = Room.databaseBuilder(
                        context.getApplicationContext(),
                        AppDatabase.class,
                        "camera_scan.db"
                ).build();
                instance = local;
            }
        }
        return local;
    }
}
