import { gql } from '@apollo/client';
import { USER_FIELDS } from '../fragments/user.fragments';

export const LOGIN_MUTATION = gql`
  ${USER_FIELDS}
  mutation Login($input: LoginInput!) {
    login(input: $input) {
      accessToken
      refreshToken
      user {
        ...UserFields
      }
    }
  }
`;

export const REGISTER_MUTATION = gql`
  ${USER_FIELDS}
  mutation Register($input: RegisterInput!) {
    register(input: $input) {
      ...UserFields
    }
  }
`;

export const GOOGLE_LOGIN_MUTATION = gql`
  ${USER_FIELDS}
  mutation GoogleLogin($idToken: String!) {
    googleLogin(idToken: $idToken) {
      accessToken
      refreshToken
      user {
        ...UserFields
      }
    }
  }
`;

export const REFRESH_TOKEN_MUTATION = gql`
  ${USER_FIELDS}
  mutation RefreshToken($token: String!) {
    refreshToken(token: $token) {
      accessToken
      refreshToken
      user {
        ...UserFields
      }
    }
  }
`;

/**
 * Bug A2 fix: the logout mutation MUST pass the refreshToken argument
 * so the server can revoke that specific token in the DB.
 * The previous version sent `mutation Logout { logout }` with no argument —
 * a GraphQL validation error swallowed by the catch block, meaning the
 * server never invalidated the token and it stayed valid for 7 days.
 */
export const LOGOUT_MUTATION = gql`
  mutation Logout($refreshToken: String!) {
    logout(refreshToken: $refreshToken)
  }
`;

