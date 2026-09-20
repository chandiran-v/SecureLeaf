/**
 * Apollo Client setup for SecureLeaf.
 *
 * - Attaches the JWT access token from Zustand store to every request.
 * - Auto-refreshes expired tokens on UNAUTHENTICATED errors.
 * - Configures the GraphQL endpoint (proxied via Vite in dev).
 */
import {
  ApolloClient,
  InMemoryCache,
  createHttpLink,
  from,
  Observable,
} from '@apollo/client';
import { setContext } from '@apollo/client/link/context';
import { onError } from '@apollo/client/link/error';
import { useAuthStore } from '../store/authStore';

// Auth link — inject Bearer token on every request
const authLink = setContext((_, { headers }) => {
  const token = useAuthStore.getState().accessToken;
  return {
    headers: {
      ...headers,
      authorization: token ? `Bearer ${token}` : '',
    },
  };
});

// HTTP link — points to Spring Boot GraphQL endpoint
const httpLink = createHttpLink({
  uri: import.meta.env.VITE_GRAPHQL_URL ?? '/graphql',
});

// Error link — handles UNAUTHENTICATED errors with silent token refresh
const errorLink = onError(({ graphQLErrors, networkError, operation, forward }) => {
  if (graphQLErrors) {
    for (const err of graphQLErrors) {
      // If we get an UNAUTHENTICATED error, try to refresh the token
      if (
        err.extensions?.classification === 'UNAUTHORIZED' ||
        err.message === 'Authentication required'
      ) {
        const refreshToken = useAuthStore.getState().refreshToken;
        if (!refreshToken) {
          useAuthStore.getState().clearAuth();
          return;
        }

        // Attempt silent refresh
        return new Observable((observer) => {
          fetch(import.meta.env.VITE_GRAPHQL_URL ?? '/graphql', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              query: `mutation RefreshToken($token: String!) {
                refreshToken(token: $token) {
                  accessToken
                  refreshToken
                  user { id email displayName roles createdAt }
                }
              }`,
              variables: { token: refreshToken },
            }),
          })
            .then((res) => res.json())
            .then((result) => {
              if (result.data?.refreshToken) {
                const { accessToken, refreshToken: newRefreshToken, user } =
                  result.data.refreshToken;
                useAuthStore.getState().setAuth(user, accessToken, newRefreshToken);

                // Retry the failed operation with the new token
                operation.setContext(({ headers = {} }) => ({
                  headers: {
                    ...headers,
                    authorization: `Bearer ${accessToken}`,
                  },
                }));

                forward(operation).subscribe({
                  next: observer.next.bind(observer),
                  error: observer.error.bind(observer),
                  complete: observer.complete.bind(observer),
                });
              } else {
                // Refresh failed — clear auth and redirect
                useAuthStore.getState().clearAuth();
                observer.error(err);
              }
            })
            .catch(() => {
              useAuthStore.getState().clearAuth();
              observer.error(err);
            });
        });
      }

      console.error(
        `[GraphQL error] Message: ${err.message}, Path: ${err.path}`,
        err.locations
      );
    }
  }

  if (networkError) {
    console.error('[Network error]', networkError);
  }
});

const client = new ApolloClient({
  link: from([errorLink, authLink, httpLink]),
  cache: new InMemoryCache(),
  defaultOptions: {
    watchQuery: { fetchPolicy: 'cache-and-network' },
  },
});

export default client;
