import { SinkingFundStatus } from './sinking-fund.models';

/** Words for the API's verdict, so colour is never the only signal. */
export function statusText(s: SinkingFundStatus): string {
  switch (s) {
    case 'FUNDED':
      return 'Fully funded';
    case 'ON_TRACK':
      return 'On track';
    case 'BEHIND':
      return 'Behind schedule';
    case 'PAST_DUE':
      return 'Past the due date';
  }
}
