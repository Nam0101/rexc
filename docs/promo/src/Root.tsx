import React from 'react';
import {Composition, Folder, Still} from 'remotion';
import {AddServerShot, AlertShot, Hero, HomeShot, TerminalShot} from './Stills';
import {PROMO_SECONDS, Promo} from './video/Promo';

export const RemotionRoot: React.FC = () => (
	<>
		<Composition id="promo" component={Promo} width={1920} height={1080} fps={30} durationInFrames={PROMO_SECONDS * 30} />
		<Folder name="Screens">
			<Still id="home" component={HomeShot} width={412} height={915} />
			<Still id="terminal" component={TerminalShot} width={412} height={915} />
			<Still id="alert" component={AlertShot} width={412} height={915} />
			<Still id="add-server" component={AddServerShot} width={412} height={915} />
		</Folder>
		<Still id="hero" component={Hero} width={1280} height={640} />
	</>
);
