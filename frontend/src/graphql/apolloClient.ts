/**
 * Apollo Client setup for SecureLeaf.
 *
 * - Attaches the JWT access token from Zustand store to every request.
 * - Recovers from an expired login: one shared token refresh + retry, or the session-expired
 *   modal (see errorLink and lib/session.ts).
 * - Configures the GraphQL endpoint (proxied via Vite in dev).
 */
import {
  ApolloClient,
  InMemoryCache,
  createHttpLink,
  from,
  Observable,
  type ServerError,
} from '@apollo/client';
import { setContext } from '@apollo/client/link/context';
import { onError } from '@apollo/client/link/error';
import { useAuthStore } from '../store/authStore';
import { recoverFromUnauthorized } from '../lib/session';

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

/** The `code` from a 401 response body (`{"code":"TOKEN_EXPIRED"}`), when Apollo parsed it. */
function unauthorizedCode(networkError: unknown): string | null | undefined {
  const err = networkError as Partial<ServerError> | null;
  if (!err || err.statusCode !== 401) return undefined; // not a 401 at all
  const body = err.result as { code?: unknown } | undefined;
  return typeof body?.code === 'string' ? body.code : null;
}

/**
 * Error link — recovers from an expired login.
 *
 * Two ways the server says "unauthorized":
 *  - HTTP 401 with `{"code": "TOKEN_EXPIRED" | "INVALID_TOKEN"}` from the JWT filter (bad or
 *    expired access token);
 *  - a GraphQL error with code UNAUTHORIZED ("Authentication required") from a resolver.
 * Either way: one shared refresh (lib/session.ts), then retry the operation once. If the
 * refresh fails, the session has ended and SessionExpiredModal explains why.
 */
const errorLink = onError(({ graphQLErrors, networkError, operation, forward }) => {
  const httpCode = unauthorizedCode(networkError);
  const gqlUnauthorized = graphQLErrors?.some(
    (err) => err.extensions?.code === 'UNAUTHORIZED' || err.message === 'Authentication required'
  );

  const alreadyRetried = operation.getContext().authRetried === true;
  if ((httpCode !== undefined || gqlUnauthorized) && !alreadyRetried) {
    return new Observable((observer) => {
      recoverFromUnauthorized(httpCode)
        .then((refreshed) => {
          if (!refreshed) {
            observer.error(networkError ?? graphQLErrors?.[0]);
            return;
          }
          // authLink runs again on retry and picks up the new access token from the store.
          operation.setContext({ authRetried: true });
          forward(operation).subscribe({
            next: observer.next.bind(observer),
            error: observer.error.bind(observer),
            complete: observer.complete.bind(observer),
          });
        })
        .catch((error) => observer.error(error));
    });
  }

  graphQLErrors?.forEach((err) =>
    console.error(`[GraphQL error] Message: ${err.message}, Path: ${err.path}`, err.locations)
  );
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
