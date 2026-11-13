ALTER TABLE categories ADD COLUMN slug TEXT;

UPDATE categories SET slug = CASE name
    WHEN 'Groceries'           THEN 'groceries'
    WHEN 'Restaurants & Cafes' THEN 'restaurants'
    WHEN 'Transport'           THEN 'transport'
    WHEN 'Fuel'                THEN 'fuel'
    WHEN 'Housing & Rent'      THEN 'housing'
    WHEN 'Utilities'           THEN 'utilities'
    WHEN 'Phone & Internet'    THEN 'phone_internet'
    WHEN 'Health & Pharmacy'   THEN 'health'
    WHEN 'Kids'                THEN 'kids'
    WHEN 'Clothing'            THEN 'clothing'
    WHEN 'Household'           THEN 'household'
    WHEN 'Entertainment'       THEN 'entertainment'
    WHEN 'Subscriptions'       THEN 'subscriptions'
    WHEN 'Travel'              THEN 'travel'
    WHEN 'Education'           THEN 'education'
    WHEN 'Gifts & Donations'   THEN 'gifts'
    WHEN 'Insurance'           THEN 'insurance'
    WHEN 'Taxes & Fees'        THEN 'taxes'
    WHEN 'Other'               THEN 'other'
    WHEN 'Salary'              THEN 'salary'
    WHEN 'Other Income'        THEN 'other_income'
    WHEN 'Transfers'           THEN 'transfers'
END;

CREATE UNIQUE INDEX categories_wallet_slug_idx ON categories (wallet_id, slug);
