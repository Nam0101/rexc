import {loadFont as loadRoboto} from '@remotion/google-fonts/Roboto';
import {loadFont} from '@remotion/fonts';
import {staticFile} from 'remotion';

// The app's dark palette (android/.../core/ui/theme/Theme.kt) and the terminal's own chrome.
export const C = {
	bg: '#0A0A0B',
	surface: '#161618',
	raised: '#242427',
	key: '#232326',
	line: '#2C2C2E',
	ink: '#F2F2F7',
	muted: '#98989F',
	accent: '#0A84FF',
	ok: '#30D158',
	amber: '#FFD60A',
	danger: '#FF453A',
	scrim: 'rgba(0,0,0,0.6)',
	// Terminal screen
	termBg: '#000000',
	chipOn: '#303031',
	chipOff: '#131313',
	dockKey: '#191919',
	// Terminal text
	tInk: '#E1E1E6',
	tGrey: '#8C8C96',
	clay: '#D97757',
	green: '#50C878',
	red: '#F05A5A',
	blue: '#6EA0F5',
	violet: '#AA82F0',
	yellow: '#E6BE5A',
	addBg: '#163C24',
	delBg: '#4B1C20',
	// Android system UI (notification shade)
	sysBg: '#000000',
	sysPanel: '#121318',
	sysCard: '#282A2F',
	sysTile: '#D9E2FF',
	sysTileInk: '#1B1B1F',
	sysTileOff: '#2F3036',
	sysTileDim: '#0C0E13',
	sysIcon: '#B7CAFF',
	sysAction: '#A8C7FA',
	sysBtn: '#3D3E43',
	sysInk: '#E3E2E9',
	sysMuted: '#C4C6D0',
} as const;

export const {fontFamily: UI} = loadRoboto('normal', {weights: ['400', '500', '700'], subsets: ['latin']});
export const MONO = 'JetBrains Mono';

loadFont({family: MONO, url: staticFile('fonts/JetBrainsMono-Regular.ttf'), weight: '400'});
loadFont({family: MONO, url: staticFile('fonts/JetBrainsMono-Bold.ttf'), weight: '700'});

/** Pixel 8 in dp: every screen is laid out at this size and scaled for output. */
export const SCREEN = {width: 412, height: 915} as const;
