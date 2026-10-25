import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../expense/expense-api.service';
import { CycleTotalView, IncomeInput, IncomeView, MaterialiseResult, PlanCycleView, TemplateInput, TemplateView } from './plan.models';

/** HTTP edge for income sources (T35) and recurring templates (T38). Returns API values untouched. */
@Injectable({ providedIn: 'root' })
export class PlanApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  cycleCurrent(): Observable<PlanCycleView> {
    return this.http.get<PlanCycleView>(`${this.base}/plan-cycles/current`);
  }
  cycleChangeStartDay(startDay: number): Observable<PlanCycleView> {
    return this.http.put<PlanCycleView>(`${this.base}/plan-cycles/start-day`, { startDay });
  }

  incomeList(): Observable<IncomeView[]> {
    return this.http.get<IncomeView[]>(`${this.base}/income-sources`);
  }
  incomeCycleTotal(): Observable<CycleTotalView> {
    return this.http.get<CycleTotalView>(`${this.base}/income-sources/cycle-total`);
  }
  incomeCreate(body: IncomeInput): Observable<IncomeView> {
    return this.http.post<IncomeView>(`${this.base}/income-sources`, body);
  }
  incomeReplace(id: string, body: IncomeInput): Observable<IncomeView> {
    return this.http.put<IncomeView>(`${this.base}/income-sources/${encodeURIComponent(id)}`, body);
  }
  incomeDelete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/income-sources/${encodeURIComponent(id)}`);
  }

  templateList(): Observable<TemplateView[]> {
    return this.http.get<TemplateView[]>(`${this.base}/recurring-templates`);
  }
  templateCreate(body: TemplateInput): Observable<TemplateView> {
    return this.http.post<TemplateView>(`${this.base}/recurring-templates`, body);
  }
  templateReplace(id: string, body: TemplateInput): Observable<TemplateView> {
    return this.http.put<TemplateView>(`${this.base}/recurring-templates/${encodeURIComponent(id)}`, body);
  }
  templateDelete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/recurring-templates/${encodeURIComponent(id)}`);
  }
  materialise(): Observable<MaterialiseResult> {
    return this.http.post<MaterialiseResult>(`${this.base}/recurring-templates/materialise`, null);
  }
}
