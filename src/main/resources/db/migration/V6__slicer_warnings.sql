-- What the slicer said about each item's model, copied from the quote when the order is
-- placed, so the owner knows which models to open in Bambu Studio before printing.
--
-- slicer_warnings: a JSON array of strings in a text column (see StringListJsonConverter);
--                  at most ten entries of at most 200 characters, '[]' when there were none
--                  and on items ordered before this migration.
-- manifold:        the --info check of the mesh; NULL when the check did not run, which is
--                  also the case for every item ordered before this migration.

ALTER TABLE order_items
    ADD COLUMN slicer_warnings TEXT    NOT NULL DEFAULT '[]',
    ADD COLUMN manifold        BOOLEAN;
