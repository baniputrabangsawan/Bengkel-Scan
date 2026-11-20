/*
 * (C) Copyright 2026 by the Bengkel Scan contributors.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package com.fr3ts0n.ecu.gui.androbd.workshop.pdf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A4 pages of wrapped text, images and rules.
 *
 * <p>Text is laid out line by line: a line that does not fit starts a new page,
 * so long notes and many photos paginate without overlap. The footer keeps its
 * own band at the bottom of every page.</p>
 */
public final class PdfPageWriter implements AutoCloseable
{
	/** A4 at 72 dpi, the size {@link PdfDocument} prints from. */
	public static final int PAGE_WIDTH = 595;
	public static final int PAGE_HEIGHT = 842;

	private static final int MARGIN_LEFT = 36;
	private static final int MARGIN_RIGHT = 36;
	private static final int MARGIN_TOP = 40;
	private static final int MARGIN_BOTTOM = 52;

	private final PdfDocument document = new PdfDocument();
	private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	private final TextPaint heading = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	private final TextPaint small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	private final Paint rule = new Paint();
	private final String footer;
	private final List<Integer> pagesWritten = new ArrayList<>();

	private PdfDocument.Page page;
	private Canvas canvas;
	private int pageNumber;
	private int cursorY;

	public PdfPageWriter(String footer)
	{
		this.footer = footer == null ? "" : footer;
		body.setTextSize(10.5f);
		body.setColor(Color.BLACK);
		heading.setTextSize(13f);
		heading.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
		heading.setColor(Color.BLACK);
		small.setTextSize(9f);
		small.setColor(Color.DKGRAY);
		rule.setColor(Color.LTGRAY);
		rule.setStrokeWidth(1f);
	}

	public static int contentWidth()
	{
		return PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT;
	}

	private static int bottomLimit()
	{
		return PAGE_HEIGHT - MARGIN_BOTTOM;
	}

	public void title(String text)
	{
		drawText(text, heading, 1.1f, 14);
	}

	public void subtitle(String text)
	{
		drawText(text, small, 1.1f, 8);
	}

	public void body(String text)
	{
		drawText(text, body, 1.15f, 6);
	}

	public void spacer(int height)
	{
		if (canvas != null && cursorY + height < bottomLimit()) cursorY += height;
	}

	public void rule()
	{
		ensureSpace(10);
		canvas.drawLine(MARGIN_LEFT, cursorY, PAGE_WIDTH - MARGIN_RIGHT, cursorY, rule);
		cursorY += 8;
	}

	/**
	 * Draw an image scaled to the text width, keeping its ratio.
	 *
	 * @return false when the bitmap is missing
	 */
	public boolean image(Bitmap bitmap, String caption)
	{
		if (bitmap == null) return false;
		int width = contentWidth();
		int height = Math.max(1, Math.round((float) bitmap.getHeight() * width / bitmap.getWidth()));
		int captionHeight = caption == null || caption.isEmpty() ? 0 : 16;
		int printable = bottomLimit() - MARGIN_TOP;
		if (height + captionHeight > printable)
		{
			float factor = (float) (printable - captionHeight) / height;
			width = Math.max(1, Math.round(width * factor));
			height = Math.max(1, Math.round(height * factor));
		}
		ensureSpace(height + captionHeight);
		int left = MARGIN_LEFT + (contentWidth() - width) / 2;
		canvas.drawBitmap(bitmap, null,
				new Rect(left, cursorY, left + width, cursorY + height), null);
		cursorY += height + 2;
		if (captionHeight > 0) drawText(caption, small, 1.1f, 4);
		return true;
	}

	public void writeTo(OutputStream output) throws IOException
	{
		finishPage();
		document.writeTo(output);
	}

	@Override
	public void close()
	{
		document.close();
	}

	/** Pages actually written, for logging and tests. */
	public int pages()
	{
		return pagesWritten.size();
	}

	/**
	 * Draw a paragraph, moving to a new page whenever the next line would not
	 * fit. A single paragraph may span pages; nothing is dropped.
	 */
	private void drawText(String text, TextPaint paint, float spacing, int spaceAfter)
	{
		if (text == null || text.isEmpty()) return;
		StaticLayout layout = StaticLayout.Builder
				.obtain(text, 0, text.length(), paint, contentWidth())
				.setLineSpacing(0f, spacing)
				.build();
		if (canvas == null) newPage();
		for (int line = 0; line < layout.getLineCount(); line++)
		{
			int top = layout.getLineTop(line);
			int bottom = layout.getLineBottom(line);
			int lineHeight = Math.max(1, bottom - top);
			if (cursorY + lineHeight > bottomLimit()) newPage();
			canvas.save();
			canvas.clipRect(MARGIN_LEFT, cursorY, PAGE_WIDTH - MARGIN_RIGHT, cursorY + lineHeight);
			canvas.translate(MARGIN_LEFT, cursorY - top);
			layout.draw(canvas);
			canvas.restore();
			cursorY += lineHeight;
		}
		cursorY += spaceAfter;
	}

	private void ensureSpace(int height)
	{
		if (canvas == null || cursorY + height > bottomLimit()) newPage();
	}

	private void newPage()
	{
		finishPage();
		pageNumber++;
		PdfDocument.PageInfo info =
				new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create();
		page = document.startPage(info);
		canvas = page.getCanvas();
		cursorY = MARGIN_TOP;
		pagesWritten.add(pageNumber);
		drawFooter();
	}

	private void drawFooter()
	{
		if (footer.isEmpty()) return;
		String text = footer + "  ·  " + pageNumber;
		float width = small.measureText(text);
		canvas.drawText(text, PAGE_WIDTH - MARGIN_RIGHT - width, PAGE_HEIGHT - 24, small);
	}

	private void finishPage()
	{
		if (page != null)
		{
			document.finishPage(page);
			page = null;
			canvas = null;
		}
	}
}
