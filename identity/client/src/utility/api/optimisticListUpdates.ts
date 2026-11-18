/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */

import type { QueryClient, QueryKey } from "@tanstack/react-query";

type SearchData<T> = { items: T[]; page?: { totalItems: number } };

type SearchParams = {
  filter?: Record<string, unknown>;
  sort?: unknown[];
  page?: { from?: number; limit?: number };
} & Record<string, unknown>;

const POLL_INTERVAL_MS = 1000;
const POLL_ATTEMPTS = 10;

// The request params follow the parent key, e.g. ["roles", "search", params]
// or ["tenants", "roles", tenantId, params].
const paramsOf = (queryKey: QueryKey, searchKey: QueryKey) =>
  queryKey[searchKey.length] as SearchParams | undefined;

type SortConfig = { field: string; order?: string };

type Scope = Record<string, unknown>;

const matchesScope = (queryKey: QueryKey, searchKey: QueryKey, scope?: Scope) =>
  Object.entries(scope ?? {}).every(
    ([key, value]) => paramsOf(queryKey, searchKey)?.[key] === value,
  );

const isUnfiltered = (params: SearchParams | undefined, scope: Scope = {}) => {
  if (!params) return true;
  const { filter, ...rest } = params;
  const hasFilter = filter !== undefined && Object.keys(filter).length > 0;
  const hasExtraFilter = Object.entries(rest).some(
    ([key, v]) =>
      key !== "sort" && key !== "page" && !(key in scope) && v !== undefined,
  );
  return !hasFilter && !hasExtraFilter;
};

const isUnfilteredFirstPage = (
  params: SearchParams | undefined,
  scope?: Scope,
) => isUnfiltered(params, scope) && !params?.page?.from;

const compareBySort = <T>(a: T, b: T, sort: SortConfig[]) => {
  for (const { field, order } of sort) {
    const valueA = String((a as Record<string, unknown>)[field] ?? "");
    const valueB = String((b as Record<string, unknown>)[field] ?? "");
    if (valueA !== valueB) {
      return (
        (valueA < valueB ? -1 : 1) * (order?.toUpperCase() === "DESC" ? -1 : 1)
      );
    }
  }
  return 0;
};

/**
 * Search endpoints are backed by an eventually consistent index, so a list
 * refetched right after a write can still show the old state. These helpers
 * patch the cached lists immediately and invalidate them only once the search
 * endpoint itself reflects the write.
 */
/**
 * Inserts `item` where the backend would place it in each cached first page.
 * Without a sort the backend returns entities in creation order, so the item
 * goes last. With a sort it goes at its sorted position, and a full page loses
 * its last row to the next page. An item that would land on a later page is
 * left out.
 */
export const insertIntoListCaches = <T>(
  qc: QueryClient,
  searchKey: QueryKey,
  item: T,
  scope?: Scope,
) => {
  for (const [queryKey, data] of qc.getQueriesData<SearchData<T>>({
    queryKey: searchKey,
    type: "active",
  })) {
    const params = paramsOf(queryKey, searchKey);
    if (
      !data ||
      !matchesScope(queryKey, searchKey, scope) ||
      !isUnfilteredFirstPage(params, scope)
    ) {
      continue;
    }

    const sort = (params?.sort ?? []) as SortConfig[];
    const items = [...data.items, item];
    if (sort.length > 0) items.sort((a, b) => compareBySort(a, b, sort));
    const limit = params?.page?.limit;
    if (limit !== undefined && items.indexOf(item) >= limit) continue;

    qc.setQueryData<SearchData<T>>(queryKey, {
      ...data,
      items: limit === undefined ? items : items.slice(0, limit),
      page: data.page && { ...data.page, totalItems: data.page.totalItems + 1 },
    });
  }
};

export const updateInListCaches = <T>(
  qc: QueryClient,
  searchKey: QueryKey,
  matches: (item: T) => boolean,
  patch: Partial<T>,
) => {
  for (const [queryKey, data] of qc.getQueriesData<SearchData<T>>({
    queryKey: searchKey,
  })) {
    if (!data?.items.some(matches)) continue;
    qc.setQueryData<SearchData<T>>(queryKey, {
      ...data,
      items: data.items.map((item) =>
        matches(item) ? { ...item, ...patch } : item,
      ),
    });
  }
};

export const removeFromListCaches = <T>(
  qc: QueryClient,
  searchKey: QueryKey,
  matches: (item: T) => boolean,
  scope?: Scope,
) => {
  for (const [queryKey, data] of qc.getQueriesData<SearchData<T>>({
    queryKey: searchKey,
  })) {
    if (!data?.items.some(matches) || !matchesScope(queryKey, searchKey, scope))
      continue;
    qc.setQueryData<SearchData<T>>(queryKey, {
      ...data,
      items: data.items.filter((item) => !matches(item)),
      page: data.page && {
        ...data.page,
        totalItems: Math.max(0, data.page.totalItems - 1),
      },
    });
  }
};

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

const pollUntil = async (isReady: () => Promise<boolean>) => {
  for (let attempt = 0; attempt < POLL_ATTEMPTS; attempt++) {
    try {
      if (await isReady()) return;
    } catch {
      // a failed poll is retried like an unconfirmed one
    }
    await sleep(POLL_INTERVAL_MS);
  }
};

/**
 * Polls `search` until `isReady` accepts its items, then invalidates
 * `invalidateKey`. Also invalidates when the change is never confirmed, so the
 * list falls back to what the backend reports instead of keeping the
 * optimistic patch.
 */
export const confirmThenInvalidate = async <T>(
  qc: QueryClient,
  invalidateKey: QueryKey,
  search: () => Promise<SearchData<T>>,
  isReady: (items: T[]) => boolean,
) => {
  try {
    await pollUntil(async () => isReady((await search()).items));
  } finally {
    await qc.invalidateQueries({ queryKey: invalidateKey });
  }
};

const cachedTotalItems = (qc: QueryClient, searchKey: QueryKey, scope: Scope) =>
  qc
    .getQueryCache()
    .findAll({ queryKey: searchKey })
    .filter(
      (query) =>
        query.state.data !== undefined &&
        matchesScope(query.queryKey, searchKey, scope) &&
        isUnfiltered(paramsOf(query.queryKey, searchKey), scope),
    )
    .sort((a, b) => b.state.dataUpdatedAt - a.state.dataUpdatedAt)
    .map((query) => (query.state.data as SearchData<unknown>).page?.totalItems)
    .find((total) => total !== undefined);

type MembershipChange<T> =
  | { type: "assign"; item?: T }
  | { type: "unassign"; matches: (item: T) => boolean };

/**
 * Optimistically adds or removes one row of a parent's sub-table (for example
 * the users assigned to a tenant). These searches cannot be filtered by ID, so
 * the change counts as confirmed once the backend's `totalItems` has moved by
 * one from the total that was cached before the patch. Without an `item` (the
 * caller only knows the ID) nothing is inserted and the row appears on confirmation.
 */
export const applyMembershipChange = async <T>(
  qc: QueryClient,
  {
    searchKey,
    scope,
    change,
    search,
    invalidateKeys,
  }: {
    searchKey: QueryKey;
    scope: Scope;
    change: MembershipChange<T>;
    search: () => Promise<SearchData<T>>;
    invalidateKeys: QueryKey[];
  },
) => {
  const baseline = cachedTotalItems(qc, searchKey, scope);
  if (change.type === "assign") {
    if (change.item) insertIntoListCaches(qc, searchKey, change.item, scope);
  } else {
    removeFromListCaches(qc, searchKey, change.matches, scope);
  }

  const invalidate = () =>
    Promise.all(
      invalidateKeys.map((queryKey) => qc.invalidateQueries({ queryKey })),
    );
  if (baseline === undefined) {
    await invalidate();
    return;
  }

  const expected = baseline + (change.type === "assign" ? 1 : -1);
  try {
    await pollUntil(async () => {
      const total = (await search()).page?.totalItems;
      return (
        total !== undefined &&
        (change.type === "assign" ? total >= expected : total <= expected)
      );
    });
  } finally {
    await invalidate();
  }
};

type SubTableConfig<T> = {
  searchKey: QueryKey;
  scope?: Scope;
  search: () => Promise<SearchData<T>>;
  invalidateKeys?: QueryKey[];
};

/** Fire-and-forget wrapper so a mutation's `onSuccess` never waits on polling. */
export const syncSubTable = <T>(
  qc: QueryClient,
  { searchKey, scope = {}, search, invalidateKeys }: SubTableConfig<T>,
  change: MembershipChange<T>,
) => {
  void applyMembershipChange(qc, {
    searchKey,
    scope,
    change,
    search,
    invalidateKeys: invalidateKeys ?? [searchKey],
  });
};
