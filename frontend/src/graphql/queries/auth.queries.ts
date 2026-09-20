import { gql } from '@apollo/client';
import { USER_FIELDS } from '../fragments/user.fragments';

export const ME_QUERY = gql`
  ${USER_FIELDS}
  query Me {
    me {
      ...UserFields
    }
  }
`;
