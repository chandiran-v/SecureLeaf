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

export const LOGOUT_MUTATION = gql`
  mutation Logout {
    logout
  }
`;
