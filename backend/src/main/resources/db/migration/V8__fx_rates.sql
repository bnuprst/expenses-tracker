-- Daily FX reference rates: `rate` is how many units of `quote` one unit of `base` buys on `as_of`.
-- `base` is the provider's own base currency (ECB: EUR); cross rates are derived from two rows.
CREATE TABLE fx_rates (
    as_of  DATE           NOT NULL,
    base   VARCHAR(3)     NOT NULL,
    quote  VARCHAR(3)     NOT NULL,
    rate   NUMERIC(24,12) NOT NULL CHECK (rate > 0),
    source TEXT           NOT NULL,
    PRIMARY KEY (as_of, base, quote)
);
CREATE INDEX fx_rates_pair_date ON fx_rates (base, quote, as_of DESC);

-- One row per calendar day we have already asked the provider about. Without this a weekend (no
-- publication) looks identical to a day never fetched, and every request would refetch it.
-- `published` records whether the provider actually returned rates for that day: an unpublished
-- recent day is PROVISIONAL (today's ECB fix may not be out yet) and is retried; `fetched_at`
-- bounds how often that retry happens.
CREATE TABLE fx_coverage (
    base       VARCHAR(3)  NOT NULL,
    as_of      DATE        NOT NULL,
    published  BOOLEAN     NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (base, as_of)
);
CREATE INDEX fx_coverage_retry ON fx_coverage (base, as_of) WHERE NOT published;
