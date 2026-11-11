# Pocket Assistant agent: what it can do, and Android's limits

## How a request is handled

1. You speak (Google's offline speech recognition) or type a command.
2. Simple, known commands ("turn on the flashlight", "call Daddy") run straight away.
3. Anything else goes to Gemma 4 E2B on the phone. It works step by step:
   it picks one tool, sees the result, checks it worked, and picks the next, until the task is done.
   If the request is unclear, it asks you a short question and continues with your answer.
4. Each step is shown on screen: Thinking, Doing, Found, Answer. The Stop button cancels at once.
5. Limits: 6 thinking steps, 10 actions and 3 minutes per task, and it never repeats the same action twice in a row.

Every tool call, whether from a rule or from Gemma, goes through the same policy checks.

## Speed

Gemma runs on the phone's CPU, so each thinking step takes several seconds. To keep tasks quick:

- Common commands skip the AI entirely: "play Darling songs", "play music", "pause / next / previous / stop the music",
  "turn on speaker", and "call Daddy on speaker" (a call, then the speaker).
- Battery and storage, missed calls, messages, calendar and "take a photo" are answered straight from the phone.
- The AI model loads when the assistant starts, not on your first request.
- Each task keeps one conversation with the AI. The long instructions are read once; later steps only send the new
  results. Before, every step re-read everything, which was most of the waiting.
- The AI is told that every tool checks its own result, so it doesn't add extra checking steps.
- The AI only sees the tools that fit the request, so its prompt is shorter and is read faster.
- The voice starts speaking after the first sentence is ready, instead of after the whole reply.
- Fixed waits in settings, Quick Settings and app tabs now finish as soon as the change shows.
- "Test model" in Settings shows how long the model took, so you can compare the CPU and GPU options.
- The AI can mark a step as the last one ("final"), or send a whole plan. Those run without another thinking round.
- The steps panel shows how long each thinking step and each action took.
- If your phone supports it, turn on the GPU option in Settings for faster thinking.

## Tools

| Area | Tools |
|---|---|
| Apps and screen | open_app, list_apps, app_section, recent_apps, read_screen, screen_task, tap_text, long_press, enter_text, scroll, go_back, go_home |
| Music | search_music, play_music (built-in player for songs on the phone), media_control (works with any player) |
| Files | search_files, open_file, read_document (PDF and text), share_file, rename_file, move_file |
| Photos and camera | find_media, open_camera, take_photo, record_video |
| Calls and messages | place_call, call_control, send_sms, send_whatsapp, open_chat, find_contact, recent_calls, recent_messages, read_notifications |
| Calendar and time | set_alarm, set_timer, create_reminder, create_event, calendar_events, delete_event |
| Device | phone_status, volume, brightness, wifi, bluetooth, mobile_data, quick_toggle, ringer_mode, do_not_disturb, lock_phone, flashlight |
| Web | web_search, web_answer, open_website |
| Memory | remember, forget |

## What needs your approval

- **A spoken yes, or a tap on the screen:** sending messages; sharing, renaming or moving files; taking a photo or starting a video; deleting a calendar event; tapping buttons such as Send, Pay, Delete, Buy, Allow or Install.
- **Your fingerprint:** any of the above after the assistant has read text written by someone else (messages, notifications, the screen, web pages, documents or calendar events), so that text can't trick it.
- **Never done:** payments, banking, password and security apps; passwords and OTP codes; app links, files or phone codes opened as websites; shell commands or code written by the AI.

## Memory

- Stored in the encrypted database on the phone.
- It only saves what you ask it to remember ("remember that I like Telugu movie songs").
- "Learn from my usage" is off until you turn it on. When on, it counts the apps, contacts and music you use most.
- You can view, edit and delete everything on the Memory screen, or turn the Memory switch off in Control Center.
- Passwords and codes are refused.

## Permissions and why

| Permission | Used for |
|---|---|
| Microphone | Wake word and voice commands |
| Contacts, Call log, Phone | Calling by name, recent calls, answering calls |
| SMS (send and read) | Sending texts; reading recent messages when you ask |
| Calendar (read and write) | Your events, adding events and reminders |
| Storage, All files access | Music, photos and documents; renaming and moving files; the Gemma model file |
| Accessibility service | Reading and using other apps' screens |
| Notification access | Reading notifications |

## Android limitations

- **Closing apps:** Android doesn't let one app close another. The assistant goes home or opens Recents instead.
- **Shutter and record buttons:** camera apps label these differently. The assistant tries the button by name, then the usual shutter spot, and only reports success when a new photo or video appears in the gallery.
- **Sharing:** Android always shows the share screen, so you pick the chat or person to finish sharing.
- **Scanned PDFs:** these have no text, so they can't be summarized.
- **Calls:** most phones only let one app use the microphone during a call, so AI call answering may not hear the caller.
- **Offline speech:** Google needs its English offline pack downloaded once (Settings in the app has a button for this). Without it, Google may use the internet.
- **Blocked screens:** the lock screen, payment apps, password managers and Android's security screens can't be controlled. This is on purpose.
- **Background limits:** Realme and other phones may stop background apps. Set the app's battery use to "No restrictions".
