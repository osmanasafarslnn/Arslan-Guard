package com.arslan.guard;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class AppAdapter extends RecyclerView.Adapter<AppAdapter.AppViewHolder> {

    public interface OnLockToggleListener {
        void onToggle(AppInfo appInfo, boolean locked);
    }

    private final List<AppInfo> appList;
    private final OnLockToggleListener listener;

    public AppAdapter(List<AppInfo> appList, OnLockToggleListener listener) {
        this.appList = appList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public AppViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_app, parent, false);
        return new AppViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull AppViewHolder holder, int position) {
        AppInfo app = appList.get(position);

        holder.txtAppName.setText(app.getAppName());
        holder.txtPackageName.setText(app.getPackageName());
        holder.imgIcon.setImageDrawable(app.getIcon());

        // Yanlış tetiklenmeyi önlemek için önce dinleyiciyi kaldırıyoruz
        holder.switchLock.setOnCheckedChangeListener(null);
        holder.switchLock.setChecked(app.isLocked());
        holder.switchLock.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                app.setLocked(isChecked);
                if (listener != null) {
                    listener.onToggle(app, isChecked);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return appList.size();
    }

    static class AppViewHolder extends RecyclerView.ViewHolder {
        ImageView imgIcon;
        TextView txtAppName;
        TextView txtPackageName;
        Switch switchLock;

        AppViewHolder(View itemView) {
            super(itemView);
            imgIcon = itemView.findViewById(R.id.imgIcon);
            txtAppName = itemView.findViewById(R.id.txtAppName);
            txtPackageName = itemView.findViewById(R.id.txtPackageName);
            switchLock = itemView.findViewById(R.id.switchLock);
        }
    }
}
