package dev.nizav.documentscanner.ui;

import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import dev.nizav.documentscanner.R;
import dev.nizav.documentscanner.data.db.PageEntity;

import java.util.ArrayList;
import java.util.List;

public final class PageAdapter extends RecyclerView.Adapter<PageAdapter.Holder> {
    public static final int MODE_GALLERY = 1;
    public static final int MODE_LIST = 2;

    public interface Listener {
        void onPageClick(PageEntity page);
    }

    private final ThumbnailLoader thumbnails;
    private final Listener listener;
    private final List<PageEntity> items = new ArrayList<>();
    private int mode = MODE_GALLERY;

    public PageAdapter(
            ThumbnailLoader thumbnails,
            Listener listener
    ) {
        this.thumbnails = thumbnails;
        this.listener = listener;
    }

    public void setMode(int mode) {
        if (this.mode == mode) {
            return;
        }
        this.mode = mode;
        notifyDataSetChanged();
    }

    public void submitList(List<PageEntity> pages) {
        items.clear();
        if (pages != null) {
            items.addAll(pages);
        }
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return mode;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        int layout = viewType == MODE_LIST
                ? R.layout.item_page_list
                : R.layout.item_page_gallery;
        MaterialCardView card = (MaterialCardView) LayoutInflater
                .from(parent.getContext())
                .inflate(layout, parent, false);
        return new Holder(card);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        PageEntity page = items.get(position);
        holder.title.setText(
                holder.itemView.getResources().getString(
                        R.string.page_number,
                        position + 1
                )
        );

        boolean indexed =
                page.ocrText != null && !page.ocrText.trim().isEmpty();
        holder.ocr.setText(
                indexed
                        ? R.string.text_ready
                        : R.string.text_not_indexed
        );

        thumbnails.load(page.filePath, holder.thumbnail);
        holder.card.setOnClickListener(v -> listener.onPageClick(page));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView thumbnail;
        final TextView title;
        final TextView ocr;

        Holder(MaterialCardView card) {
            super(card);
            this.card = card;
            thumbnail = card.findViewById(R.id.pageThumbnail);
            title = card.findViewById(R.id.pageTitle);
            ocr = card.findViewById(R.id.pageOcrState);
        }
    }
}
