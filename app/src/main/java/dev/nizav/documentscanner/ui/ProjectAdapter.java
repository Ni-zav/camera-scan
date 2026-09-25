package dev.nizav.documentscanner.ui;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import dev.nizav.documentscanner.R;
import dev.nizav.documentscanner.data.db.ProjectRow;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;

public final class ProjectAdapter
        extends RecyclerView.Adapter<ProjectAdapter.Holder> {

    public interface Listener {
        void onProjectClick(ProjectRow row);
    }

    private final ThumbnailLoader thumbnails;
    private final Listener listener;
    private final List<ProjectRow> items = new ArrayList<>();
    private final DateFormat dateFormat = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT
    );

    public ProjectAdapter(
            ThumbnailLoader thumbnails,
            Listener listener
    ) {
        this.thumbnails = thumbnails;
        this.listener = listener;
    }

    public void submitList(List<ProjectRow> rows) {
        items.clear();
        if (rows != null) {
            items.addAll(rows);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        MaterialCardView card = (MaterialCardView) LayoutInflater
                .from(parent.getContext())
                .inflate(R.layout.item_project, parent, false);
        return new Holder(card);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        ProjectRow row = items.get(position);
        holder.title.setText(row.project.name);
        holder.meta.setText(
                holder.itemView.getResources().getQuantityString(
                        R.plurals.page_count,
                        row.pageCount,
                        row.pageCount
                )
        );
        holder.date.setText(
                dateFormat.format(new java.util.Date(row.project.updatedAt))
        );
        thumbnails.load(row.coverPath, holder.cover);
        holder.card.setOnClickListener(v -> listener.onProjectClick(row));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView cover;
        final TextView title;
        final TextView meta;
        final TextView date;

        Holder(MaterialCardView card) {
            super(card);
            this.card = card;
            cover = card.findViewById(R.id.projectCover);
            title = card.findViewById(R.id.projectTitle);
            meta = card.findViewById(R.id.projectMeta);
            date = card.findViewById(R.id.projectDate);
        }
    }
}
