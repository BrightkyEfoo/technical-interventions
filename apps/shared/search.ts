import { debounceTime, distinctUntilChanged, type SchedulerLike } from 'rxjs';

export function searchTerms(delay = 250, scheduler?: SchedulerLike) {
  return (terms: import('rxjs').Observable<string>) => terms.pipe(
    debounceTime(delay, scheduler),
    distinctUntilChanged(),
  );
}
