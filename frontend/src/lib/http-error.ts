import axios from 'axios';
import type { ApiErrorPayload } from '@/types/api';

export function getErrorPayload(error: unknown): ApiErrorPayload {
  const fallbackResponse = (error as { response?: { data?: ApiErrorPayload } })?.response?.data;

  if (fallbackResponse) {
    return {
      code: fallbackResponse.code,
      message: fallbackResponse.message ?? '请求失败，请稍后重试',
      traceId: fallbackResponse.traceId,
    };
  }

  if (!axios.isAxiosError(error)) {
    return {
      message: '请求失败，请稍后重试',
    };
  }

  return {
    code: error.response?.data?.code,
    message: error.response?.data?.message ?? error.message,
    traceId: error.response?.data?.traceId,
  };
}
