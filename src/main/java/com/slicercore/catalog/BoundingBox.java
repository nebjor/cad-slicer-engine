package com.slicercore.catalog;


/** Axis-aligned model extent in millimetres as reported by the slicer. */
public record BoundingBox(double widthMm, double depthMm, double heightMm) {

    public static final BoundingBox EMPTY = new BoundingBox(0, 0, 0);
}
