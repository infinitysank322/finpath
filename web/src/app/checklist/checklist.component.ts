import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { PaiseInrPipe } from '../paise-inr.pipe';
import { errorMessage } from '../plan/plan-format';
import { FpCardComponent } from '../ui/fp-card.component';
import { FpPageHeaderComponent } from '../ui/fp-page-header.component';
import { ChecklistApiService } from './checklist-api.service';
import { cellLabel, cycleLabel } from './checklist-format';
import { ChecklistItem, ChecklistView, StreakView } from './checklist.models';

const TYPE_TEXT: Record<ChecklistItem['type'], string> = { EMI: 'EMI', SIP: 'SIP', GOAL_TRANSFER: 'Goal transfer' };

/** Monthly checklist, streak and 12-cycle consistency heatmap. All figures (streak, counts, flags) come from the API. */
@Component({
  selector: 'app-checklist',
  standalone: true,
  imports: [PaiseInrPipe, FpPageHeaderComponent, FpCardComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './checklist.component.html',
  styleUrl: './checklist.css',
})
export class ChecklistComponent implements OnInit {
  private readonly api = inject(ChecklistApiService);

  readonly cycleLabel = cycleLabel;
  readonly cellLabel = cellLabel;
  readonly typeText = (t: ChecklistItem['type']) => TYPE_TEXT[t] ?? t;

  readonly view = signal<ChecklistView | null>(null);
  readonly streak = signal<StreakView | null>(null);
  readonly loading = signal(true);
  readonly busyId = signal<string | null>(null);
  readonly generating = signal(false);
  readonly error = signal('');

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.error.set('');
    this.api.current().subscribe({
      next: (v) => {
        this.view.set(v);
        this.loading.set(false);
        this.loadStreak();
      },
      error: (e: HttpErrorResponse) => this.fail(e, 'Loading the checklist'),
    });
  }

  generate(): void {
    this.generating.set(true);
    this.error.set('');
    this.api.generate().subscribe({
      next: (v) => {
        this.view.set(v);
        this.generating.set(false);
        this.loadStreak();
      },
      error: (e: HttpErrorResponse) => {
        this.generating.set(false);
        this.fail(e, 'Creating the checklist');
      },
    });
  }

  /** Marks one item done on the server, then re-reads the list and streak so every number is the API's. */
  markDone(item: ChecklistItem): void {
    if (item.done || this.busyId() !== null) return;
    this.busyId.set(item.id);
    this.error.set('');
    this.api.markDone(item.id).subscribe({
      next: () => {
        this.busyId.set(null);
        this.api.current().subscribe({
          next: (v) => {
            this.view.set(v);
            this.loadStreak();
          },
          error: (e: HttpErrorResponse) => this.fail(e, 'Refreshing the checklist'),
        });
      },
      error: (e: HttpErrorResponse) => {
        this.busyId.set(null);
        this.fail(e, 'Ticking this item');
      },
    });
  }

  private loadStreak(): void {
    this.api.streak().subscribe({
      next: (s) => this.streak.set(s),
      error: () => this.streak.set(null),
    });
  }

  private fail(e: HttpErrorResponse, action: string): void {
    this.loading.set(false);
    this.error.set(errorMessage(e.status, action));
  }
}
