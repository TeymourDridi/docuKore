import { Component, inject } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { LanguageService } from '../../i18n/language.service';

@Component({
  selector: 'app-public-shell',
  imports: [RouterOutlet],
  templateUrl: './public-shell.html',
  styleUrl: './public-shell.scss',
})
export class PublicShell {
  protected readonly i18n = inject(LanguageService);
}
