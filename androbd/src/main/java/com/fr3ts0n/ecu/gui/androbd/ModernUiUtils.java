/*
 * (C) Copyright 2015 by fr3ts0n <erwin.scheuch-heilig@gmx.at>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */

package com.fr3ts0n.ecu.gui.androbd;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Utility class for resource optimization and modern UI practices
 * Compatible with Android 4.1+
 */
public class ModernUiUtils {

    /** Keep XML padding intact and apply system/app-bar spacing once at the content root. */
    public static void applyScreenInsets(Activity activity) {
        View root = activity.findViewById(android.R.id.content);
        if (root == null || root.getTag(R.id.workshop_insets_applied) != null) return;
        root.setTag(R.id.workshop_insets_applied, Boolean.TRUE);
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        final int[] location = new int[2];
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            view.post(() -> {
                WindowInsetsCompat current = ViewCompat.getRootWindowInsets(view);
                Insets safe = (current == null ? insets : current).getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                view.getLocationOnScreen(location);
                int contentTop = location[1];
                int overlap = Math.max(0, safe.top - contentTop);
                View bar = activity.findViewById(androidx.appcompat.R.id.action_bar);
                if (bar != null && bar.isShown()) {
                    bar.getLocationOnScreen(location);
                    overlap = Math.max(overlap, location[1] + bar.getHeight() - contentTop);
                }
                view.setPadding(left + safe.left, top + overlap,
                        right + safe.right, bottom + safe.bottom);
            });
            return insets;
        });
        root.post(() -> ViewCompat.requestApplyInsets(root));
    }

    /**
     * Optimize ListView performance with modern techniques
     * @param listView ListView to optimize
     */
    public static void optimizeListView(ListView listView) {
        if (listView == null) return;
        
        // Enable smooth scrolling
        listView.setSmoothScrollbarEnabled(true);
        
        // Optimize scrolling performance  
        listView.setScrollingCacheEnabled(true);
        
        // Enable fast scroll for large lists
        listView.setFastScrollEnabled(true);
        
        // Optimize drawing cache
        listView.setDrawingCacheEnabled(true);
        
        // Set optimal scrollbar properties
        listView.setScrollbarFadingEnabled(true);
    }

    /**
     * Recursively cleanup view hierarchy to prevent memory leaks
     * @param view Root view to cleanup
     */
    public static void cleanupViewHierarchy(View view) {
        if (view == null) return;
        try {
            if (view instanceof ViewGroup viewGroup) {
                for (int i = 0; i < viewGroup.getChildCount(); i++) {
                    cleanupViewHierarchy(viewGroup.getChildAt(i));
                }
            }

            // Clear any background drawables to prevent memory leaks
            view.setBackground(null);

            // Clear any click listeners
            view.setOnClickListener(null);
            view.setOnTouchListener(null);
        } catch (Exception e) {
            // Ignore any errors
        }
    }

}