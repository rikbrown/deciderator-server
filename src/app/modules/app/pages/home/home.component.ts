import {AfterViewInit, Component, OnInit, ViewChild, ViewEncapsulation} from '@angular/core';
import {JoinComponent} from './join/join.component';
import {CreateComponent} from './create/create.component';
import {DecideratorSocketService} from '../../../../core/services/deciderator-socket/deciderator-socket.service';
import {takeUntil} from 'rxjs/operators';
import {OnDestroyMixin, untilComponentDestroyed} from '@w11k/ngx-componentdestroyed';
import {SetUsernameModalComponent} from "../../set-username-modal/set-username-modal.component";

@Component({
  selector: 'app-home',
  templateUrl: './home.component.html',
  styleUrls: ['./home.component.scss'],
  encapsulation: ViewEncapsulation.None
})
export class HomeComponent extends OnDestroyMixin implements OnInit, AfterViewInit {
  @ViewChild(JoinComponent) joinModal: JoinComponent;
  @ViewChild(CreateComponent) createModal: CreateComponent;
  @ViewChild(SetUsernameModalComponent) setUsernameModal: SetUsernameModalComponent;
  sessionId: string;
  onlineSessionIds: string[];

  constructor(
    private decideratorClient: DecideratorSocketService
  ) {
    super();
  }

  ngOnInit(): void {
    this.decideratorClient.activeSessionsMessageSubject
      .pipe(untilComponentDestroyed(this))
      .subscribe(msg => {
        this.sessionId = msg?.sessionId;
        this.onlineSessionIds = msg?.onlineSessionIds ?? [];
      });
  }

  ngAfterViewInit(): void {
    // this.createModal.open();
  }

}
