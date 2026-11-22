import { useEffect, useRef, useState } from 'react';

const PREVIEW_SONG_COUNT = 3;

const reasons = {
  TITLE_VARIANT: 'Spotify lists this recording with a version label.',
  ARTIST_MISMATCH: 'The title matches, but the artist credit differs.',
  MULTIPLE_RECORDINGS: 'Spotify returned multiple recordings.',
  NO_EXACT_TITLE: 'No exact title match in the Spotify results.',
  MISSING_TITLE: 'The setlist did not give this song a title.',
};

function spotifyTrackLink(candidate) {
  if (candidate.spotifyUrl?.startsWith('https://open.spotify.com/track/')) {
    return candidate.spotifyUrl;
  }
  const track = /^spotify:track:([a-zA-Z0-9]+)$/.exec(candidate.uri);
  if (track) {
    return `https://open.spotify.com/track/${track[1]}`;
  }
  return null;
}

function CandidateOption({ candidate, songId, checked, onChoose }) {
  const link = spotifyTrackLink(candidate);
  const image = candidate.album?.images?.find(({ url }) => url.startsWith('https://'));

  return (
    <div className="candidate-option">
      <label>
        <input type="radio" name={`match-${songId}`} checked={checked} onChange={onChoose} />
        <span className="candidate-details">
          {image && <img src={image.url} alt="" width="48" height="48" loading="lazy" />}
          <span>
            <strong>{candidate.name}</strong>
            {candidate.artists.length > 0 && <span>{candidate.artists.join(', ')}</span>}
            {candidate.album?.name && <span>{candidate.album.name}</span>}
          </span>
        </span>
      </label>
      {link && <a href={link} target="_blank" rel="noreferrer">Open in Spotify</a>}
    </div>
  );
}

function SongPreviewList({ items, listClass, label, renderSong }) {
  const remainingSongs = items.slice(PREVIEW_SONG_COUNT);

  return (
    <>
      <div className="track-scroll" role="region" aria-label={label} tabIndex="0">
        <ol className={listClass}>{items.slice(0, PREVIEW_SONG_COUNT).map(renderSong)}</ol>
      </div>
      {remainingSongs.length > 0 && (
        <details className="more-songs">
          <summary>{remainingSongs.length} more {remainingSongs.length === 1 ? 'song' : 'songs'}</summary>
          <div className="track-scroll" role="region" aria-label={label + ' continued'} tabIndex="0">
            <ol className={listClass} start={PREVIEW_SONG_COUNT + 1}>
              {remainingSongs.map((song, index) => renderSong(song, index + PREVIEW_SONG_COUNT))}
            </ol>
          </div>
        </details>
      )}
    </>
  );
}

function retryDelay(header) {
  if (!header) {
    return 0;
  }
  if (/^\d+$/.test(header)) {
    return Number(header) * 1000;
  }
  return Math.max(0, Date.parse(header) - Date.now()) || 0;
}

export default function MatchReview({ setlistId, tracks, onLogin }) {
  const [songs, setSongs] = useState(null);
  const [choices, setChoices] = useState({});
  const [status, setStatus] = useState('idle');
  const [message, setMessage] = useState('');
  const [retryAt, setRetryAt] = useState(0);
  const reviewTitle = useRef(null);
  const connectLink = useRef(null);

  useEffect(() => {
    if (status === 'ready') {
      reviewTitle.current?.focus();
    } else if (status === 'sign-in') {
      connectLink.current?.focus();
    }
  }, [status]);

  useEffect(() => {
    if (!retryAt) {
      return;
    }
    const timeout = window.setTimeout(() => setRetryAt(0), Math.min(2147483647, Math.max(0, retryAt - Date.now())));
    return () => window.clearTimeout(timeout);
  }, [retryAt]);

  async function loadPreview() {
    setStatus('loading');
    setMessage('');
    try {
      const response = await fetch('/api/v1/playlist-previews', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ setlistId }),
      });
      if (!response.ok) {
        const error = await response.json().catch(() => null);
        if (response.status === 401) {
          setStatus('sign-in');
          setMessage('Connect Spotify to review these songs.');
          return;
        }
        const delay = retryDelay(response.headers.get('Retry-After'));
        if (delay > 0) {
          setRetryAt(Date.now() + delay);
        }
        setStatus('error');
        setMessage(error?.message || 'Could not load Spotify matches. Please try again.');
        return;
      }
      const preview = await response.json();
      if (preview.setlistId !== setlistId || !Array.isArray(preview.songs)) {
        throw new Error('Unexpected preview response');
      }

      const initialChoices = {};
      for (const song of preview.songs) {
        if (song.status === 'MATCHED') {
          initialChoices[song.songId] = song.candidates[0].uri;
        }
      }
      setChoices(initialChoices);
      setSongs(preview.songs);
      setStatus('ready');
      setMessage(`Loaded ${preview.songs.length} songs for review.`);
    } catch {
      setStatus('error');
      setMessage('Could not load Spotify matches. Please try again.');
    }
  }

  function choose(songId, uri) {
    setChoices((previous) => ({ ...previous, [songId]: uri }));
  }

  let actionLabel = 'Review Spotify matches';
  if (status === 'loading') {
    actionLabel = 'Loading matches…';
  } else if (status === 'error') {
    actionLabel = 'Try again';
  }

  return (
    <section className="match-review" aria-label="Spotify match review">
      {!songs && (
        <SongPreviewList items={tracks} listClass="preview-tracks" label="Setlist songs"
          renderSong={(song, index) => <li key={index}>{song || 'Unknown song'}</li>} />
      )}
      {status === 'sign-in' && (
        <a ref={connectLink} className="spotify-login" href="/api/v1/auth/login" onClick={onLogin}>Connect Spotify</a>
      )}
      {status !== 'sign-in' && status !== 'ready' && (
        <button className="search-button" type="button" onClick={loadPreview} disabled={status === 'loading' || retryAt > 0}>
          {actionLabel}
        </button>
      )}
      <p className="match-status" role="status">{message}</p>
      {songs && (
        <>
          <h4 ref={reviewTitle} tabIndex="-1">Review Spotify matches</h4>
          <div className="match-source">
            <span>Matches from</span>
            <img src="https://developer-assets.spotifycdn.com/images/guidelines/design/full-logo-framed.svg" alt="Spotify" />
          </div>
          <SongPreviewList items={songs} listClass="match-list" label="Spotify match choices"
            renderSong={(song) => {
              const title = song.title || 'Unknown song';
              return (
                <li key={song.songId}>
                  {song.status === 'UNMATCHED' && (
                    <div className="unmatched-song">
                      <strong>{title}</strong>
                      <p>{reasons[song.reason] || 'No Spotify match.'} This song will be skipped.</p>
                    </div>
                  )}
                  {song.status !== 'UNMATCHED' && (
                    <fieldset className="song-choices">
                      <legend>{title}</legend>
                      <p>{reasons[song.reason] || 'Matched automatically.'}</p>
                      {song.status === 'NEEDS_REVIEW' && <p>Choose a recording or skip this song.</p>}
                      {song.candidates.map((candidate) => (
                        <CandidateOption key={candidate.uri} candidate={candidate} songId={song.songId}
                          checked={choices[song.songId] === candidate.uri}
                          onChoose={() => choose(song.songId, candidate.uri)} />
                      ))}
                      <label className="skip-choice">
                        <input type="radio" name={`match-${song.songId}`} checked={choices[song.songId] === ''}
                          onChange={() => choose(song.songId, '')} />
                        Skip this song
                      </label>
                    </fieldset>
                  )}
                </li>
              );
            }} />
          <p className="field-hint">{Object.values(choices).filter(Boolean).length} selected · Draft choices only</p>
        </>
      )}
    </section>
  );
}
