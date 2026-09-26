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

// AUTH-07 — always resolves true; the UI must show the same generic message whether or
// not the email has an account (enumeration-safe, D8).
export const REQUEST_PASSWORD_RESET_MUTATION = gql`
  mutation RequestPasswordReset($email: String!) {
    requestPasswordReset(email: $email)
  }
`;

export const RESET_PASSWORD_MUTATION = gql`
  mutation ResetPassword($token: String!, $newPassword: String!) {
    resetPassword(token: $token, newPassword: $newPassword)
  }
`;

