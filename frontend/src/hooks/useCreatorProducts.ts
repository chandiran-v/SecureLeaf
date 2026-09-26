import { useMutation, useQuery } from '@apollo/client';
import { useCallback } from 'react';
import { MY_PRODUCTS } from '../graphql/queries/product.queries';
import {
  CREATE_PRODUCT,
  UNPUBLISH_PRODUCT,
  DELETE_PRODUCT,
  BECOME_CREATOR,
  RETRY_PROCESSING,
  REPUBLISH_PRODUCT,
} from '../graphql/mutations/product.mutations';
import { REFRESH_TOKEN_MUTATION } from '../graphql/mutations/auth.mutations';
import { useAuthStore } from '../store/authStore';
import type { Product, User, AuthPayload } from '../types';

/**
 * Custom hook encapsulating all creator product operations.
 * Mirrors the shape of useAuth.ts — mutations wrapped in useCallback,
 * queries exposed with loading/error state.
 *
 * Why immediately call refreshToken after becomeCreator?
 * The becomeCreator mutation updates the DB (adds CREATOR role), but the
 * existing 15-minute JWT still carries only ROLE_BUYER. Role-gated operations
 * (createProduct, upload) will fail with 403 until the token is refreshed.
 * Calling refreshToken right after becomeCreator gives the user a new JWT
 * that includes ROLE_CREATOR — seamless UX.
 */
export function useCreatorProducts() {
  const { setAuth, refreshToken: storedRefreshToken } = useAuthStore();

  // ── Queries ──────────────────────────────────────────────────────────────

  const { data, loading: productsLoading, error: productsError, refetch } =
    useQuery<{ myProducts: Product[] }>(MY_PRODUCTS, {
      fetchPolicy: 'cache-and-network',
    });

  // ── Mutations ────────────────────────────────────────────────────────────

  const [createProductMutation, { loading: createLoading }] =
    useMutation<{ createProduct: Product }>(CREATE_PRODUCT, {
      refetchQueries: [{ query: MY_PRODUCTS }],
    });

  const [unpublishProductMutation] =
    useMutation<{ unpublishProduct: Product }>(UNPUBLISH_PRODUCT, {
      refetchQueries: [{ query: MY_PRODUCTS }],
    });

  const [deleteProductMutation] =
    useMutation<{ deleteProduct: boolean }>(DELETE_PRODUCT, {
      refetchQueries: [{ query: MY_PRODUCTS }],
    });

  const [retryProcessingMutation] =
    useMutation<{ retryProcessing: Product }>(RETRY_PROCESSING, {
      refetchQueries: [{ query: MY_PRODUCTS }],
    });

  const [republishProductMutation] =
    useMutation<{ republishProduct: Product }>(REPUBLISH_PRODUCT, {
      refetchQueries: [{ query: MY_PRODUCTS }],
    });

  const [becomeCreatorMutation] =
    useMutation<{ becomeCreator: User }>(BECOME_CREATOR);

  const [refreshTokenMutation] =
    useMutation<{ refreshToken: AuthPayload }>(REFRESH_TOKEN_MUTATION);

  // ── Actions ──────────────────────────────────────────────────────────────

  const createProduct = useCallback(
    async (input: {
      title: string;
      description: string;
      pricePaise: number;
      categoryId: string;
      tags: string[];
      freePreviewPages: number;
    }) => {
      const { data } = await createProductMutation({ variables: { input } });
      return data?.createProduct;
    },
    [createProductMutation]
  );

  const unpublishProduct = useCallback(
    async (id: string) => {
      await unpublishProductMutation({ variables: { id } });
    },
    [unpublishProductMutation]
  );

  const deleteProduct = useCallback(
    async (id: string) => {
      await deleteProductMutation({ variables: { id } });
    },
    [deleteProductMutation]
  );

  const retryProcessing = useCallback(
    async (productId: string) => {
      await retryProcessingMutation({ variables: { productId } });
    },
    [retryProcessingMutation]
  );

  const republishProduct = useCallback(
    async (productId: string) => {
      await republishProductMutation({ variables: { productId } });
    },
    [republishProductMutation]
  );

  /**
   * becomeCreator → immediately refreshes the JWT so the new ROLE_CREATOR
   * claim is present in subsequent requests.
   */
  const becomeCreator = useCallback(
    async (input: { bio?: string; payoutEmail?: string; payoutUpi?: string }) => {
      await becomeCreatorMutation({ variables: { input } });

      // Refresh the JWT — the becomeCreator DB update doesn't invalidate the
      // current access token. We must exchange the refresh token for a new
      // access token that carries ROLE_CREATOR.
      if (storedRefreshToken) {
        const { data } = await refreshTokenMutation({
          variables: { token: storedRefreshToken },
        });
        if (data?.refreshToken) {
          setAuth(
            data.refreshToken.user,
            data.refreshToken.accessToken,
            data.refreshToken.refreshToken
          );
        }
      }
    },
    [becomeCreatorMutation, refreshTokenMutation, storedRefreshToken, setAuth]
  );

  return {
    products: data?.myProducts ?? [],
    productsLoading,
    productsError,
    refetchProducts: refetch,
    createProduct,
    createLoading,
    unpublishProduct,
    deleteProduct,
    retryProcessing,
    republishProduct,
    becomeCreator,
  };
}
