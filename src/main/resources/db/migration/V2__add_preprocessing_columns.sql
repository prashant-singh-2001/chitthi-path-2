-- Add pre-processing columns to page table
ALTER TABLE page ADD COLUMN raw_image_key VARCHAR(500);
ALTER TABLE page ADD COLUMN preprocessed BOOLEAN NOT NULL DEFAULT FALSE;
