import type {HostCard, InboxItem} from './screens/Home';
import {C} from './theme';

// The staged demo: two servers, agents in every state. Names are generic on purpose.

export const CLAUDE_ASK = 'Make this edit to Dock.kt?';

export const claudeInbox = (extra: Partial<InboxItem> = {}): InboxItem => ({
	agent: 'claude',
	state: 'blocked',
	title: CLAUDE_ASK,
	meta: 'claude · wants permission · studio › rexc › claude',
	preview: [' Make this edit to Dock.kt?', ' ❯ 1. Yes', '   2. Yes, allow edits this session…'],
	...extra,
});

export const cargoInbox: InboxItem = {
	agent: 'cargo',
	state: 'error',
	title: 'Build failed: 2 errors',
	meta: 'cargo · failed · build-box › ingest › build',
};

export type ClaudeStage = 'blocked' | 'working' | 'done';

export const hosts = (claude: ClaudeStage = 'blocked'): HostCard[] => [
	{
		name: 'studio',
		sessions: [
			{
				label: 'rexc',
				agent: 'claude',
				state: claude,
				activity: {
					blocked: `claude · ${CLAUDE_ASK}`,
					working: 'claude · Running unit tests',
					done: 'claude · Dock keys repeat while held',
				}[claude],
			},
			{label: 'api-server', agent: 'codex', state: 'working', activity: 'codex · Running cargo test'},
			{label: 'blog', agent: 'gemini', state: 'done', activity: 'gemini · Draft ready: rex-on-android.md'},
			{label: 'dotfiles', agent: 'shell', activity: '1 tab'},
		],
	},
	{
		name: 'build-box',
		sessions: [
			{label: 'ingest', agent: 'cargo', state: 'error', activity: 'cargo · Build failed: 2 errors'},
			{label: 'billing', agent: 'copilot', state: 'working', activity: 'copilot · Writing migration 0042'},
		],
	},
];

// --- Terminal screens -------------------------------------------------------

/** One run of text: [text, color, bold, background]. */
export type Seg = readonly [string, string?, boolean?, string?];
export type Line = readonly Seg[];

const I = C.tInk;
const G = C.tGrey;

const head: Line[] = [
	[[' ✻ ', C.clay], ['claude', I, true], [' · ~/src/rexc', G]],
	[],
	[[' > ', G], ['Why does a dock key fire only once?', I]],
	[],
	[[' ● ', C.clay], ['Dock.kt wires each key to', I]],
	[['   detectTapGestures, which fires once per', I]],
	[['   tap. Holding the key does nothing more.', I]],
	[],
	[[' > ', G], ['Make the dock keys repeat while held', I]],
	[],
	[[' ● ', C.clay], ['Repeat starts after 400 ms, then fires', I]],
	[['   every 50 ms until the finger lifts.', I]],
	[],
	[[' ● ', C.green], ['Read', I, true], ['(terminal/Dock.kt)', G]],
	[['   ⎿  Read 214 lines', G]],
	[],
];

const DIFF_END = 50;
const diff = (num: string, sign: ' ' | '+' | '-', text: string): Line => {
	const body = `${sign} ${text}`;
	const color = sign === '+' ? C.green : sign === '-' ? C.red : I;
	const bg = sign === '+' ? C.addBg : sign === '-' ? C.delBg : undefined;
	return [['   '], [num, G], [body.padEnd(DIFF_END - 3 - num.length), color, false, bg]];
};

/** Rows 17..27 hold the edit box; [boxRows] tells the screen where to draw its frame. */
export const claudeBlocked: Line[] = [
	...head,
	[[' ● ', C.clay], ['Update', I, true], ['(terminal/Dock.kt)', G]],
	[],
	[['   '], ['Edit file', I, true], [' Dock.kt', G]],
	[],
	diff(' 88 ', ' ', 'Modifier.pointerInput(key) {'),
	diff(' 89 ', '-', '  detectTapGestures { send(key) }'),
	diff(' 89 ', '+', '  repeatWhilePressed(400, 50) {'),
	diff(' 90 ', '+', '    send(key)'),
	diff(' 91 ', '+', '  }'),
	diff(' 92 ', ' ', '}'),
	[],
	[],
	[],
	[[' Make this edit to ', I], ['Dock.kt', I, true], ['?', I]],
	[[' ❯ 1. Yes', C.clay]],
	[['   2. Yes, allow edits this session', I]],
	[['   3. No, and tell Claude why', I]],
];
export const claudeBox = {fromRow: 17, toRow: 27, fromCol: 1, toCol: 52};

export const claudeWorking = (tests: number, seconds: number, spin: string): Line[] => [
	...head,
	[[' ● ', C.green], ['Update', I, true], ['(terminal/Dock.kt)', G]],
	[['   ⎿  Updated with ', G], ['4 additions', C.green], [', ', G], ['1 removal', C.red]],
	[],
	[[' ● ', C.clay], ['Bash', I, true], ['(./gradlew testDebugUnitTest)', G]],
	...[
		'DockRepeatTest > starts after 400 ms',
		'DockRepeatTest > fires every 50 ms',
		'DockRepeatTest > stops on release',
		'KeysTest > ctrl chords map to C0',
	]
		.slice(0, tests)
		.map((t): Line => [['   ⎿  ', G], [t, I], [' ✓', C.green]]),
	[],
	[[` ${spin} `, C.clay], ['Running tests…', C.clay], [` (${seconds}s · esc to interrupt)`, G]],
];

export const claudeDone: Line[] = [
	...head,
	[[' ● ', C.green], ['Update', I, true], ['(terminal/Dock.kt)', G]],
	[['   ⎿  Updated with ', G], ['4 additions', C.green], [', ', G], ['1 removal', C.red]],
	[],
	[[' ● ', C.green], ['Bash', I, true], ['(./gradlew testDebugUnitTest)', G]],
	[['   ⎿  BUILD SUCCESSFUL · 4 tests passed', G]],
	[],
	[[' ● ', C.clay], ['Done. Dock keys repeat while held: the', I]],
	[['   first repeat at 400 ms, then every 50 ms.', I]],
	[['   Three new tests cover start, rate, release.', I]],
	[],
	[[' > ', G], ['▌', G]],
];

// `git log --graph` of this repository, trimmed to the phone's width.
const Y = C.yellow;
export const gitLog: Line[] = [
	[['* '], ['c42c0f9', Y], [' Home and Terminal move: snappy mot..']],
	[['* '], ['77b1eb1', Y], [' Add a server by QR: rexc qr prints..']],
	[['* '], ['d47d7a6', Y], [' Tailnet hosts keep TLS when the ad..']],
	[['* '], ['9bba23e', Y], [' Tap in a mouse-tracking program cl..']],
	[['* '], ['f791207', Y], [' Terminal reads font, size and loca..']],
	[['*   '], ['7885882', Y], [" Merge branch 'feat/render'"]],
	[['|\\', C.red]],
	[['| ', C.red], ['* '], ['ed2b6a3', Y], [' Tap clicks the mouse-tracking prog..']],
	[['| ', C.red], ['* '], ['5cfe1bd', Y], [' Trim plain blank ends of rows befo..']],
	[['| ', C.red], ['* '], ['a553b0d', Y], [' Render path on Android: row cache,..']],
	[['| ', C.red], ['* '], ['81cc1c4', Y], [' Rust render path: row revisions, s..']],
	[['* '], ['| ', C.red], ['eeffb0a', Y], [' Terminal honours the settings: ope..']],
	[['* '], ['|   ', C.red], ['37cc308', Y], [" Merge branch 'feat/dock-palette'"]],
	[['|\\ ', C.green], ['\\', C.red]],
	[['| ', C.green], ['* '], ['| ', C.red], ['f416afc', Y], [' Terminal: editable dock with snipp..']],
	[['| ', C.green], ['|/', C.red]],
	[['* '], ['| ', C.red], ['f8303c9', Y], [' Home: battery-optimisation hint wi..']],
	[['~/src/rexc', C.green], [' main', C.violet], [' ❯ ', I], ['█', I]],
];
