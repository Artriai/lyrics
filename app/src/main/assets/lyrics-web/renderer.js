(function () {
  var lyricsEl = document.getElementById("lyrics");
  var emptyEl = document.getElementById("empty");
  var stageEl = document.getElementById("stage");
  var lyricsViewportEl = null;

  function getScrollContainer() {
    if (!lyricsViewportEl) {
      lyricsViewportEl = document.querySelector(".lyrics-viewport") || stageEl;
    }
    return state.isFullLyricsMode ? lyricsViewportEl : stageEl;
  }
  var trackTitleEl = document.getElementById("trackTitle");
  var trackArtistEl = document.getElementById("trackArtist");
  var vrrKeepaliveEl = document.getElementById("vrr-keepalive");

  var state = {
    track: null,
    lyrics: [],
    activeIndex: -1,
    readingMode: 1, // 0=None, 1=Romaji, 2=Furigana
    isFullLyricsMode: false
  };

  var playback = {
    positionMs: 0,
    isPlaying: false,
    updatedAt: 0,
    visualOffset: 0,
    resumeTime: 0
  };

  var layoutGeneration = 0;
  var prevActiveIndex = -1;
  var cachedActiveSyllables = [];

  function recacheActiveSyllables() {
    cachedActiveSyllables = [];
    var activeLineEl = lyricsEl.querySelector(".line.active");
    if (!activeLineEl) return;
    
    var syllables = activeLineEl.querySelectorAll(".syllable");
    for (var i = 0; i < syllables.length; i++) {
      var el = syllables[i];
      cachedActiveSyllables.push({
        el: el,
        start: Number(el.getAttribute("data-start")) || 0,
        end: Number(el.getAttribute("data-end")) || 0,
        lastClass: "",
        lastProgress: -1
      });
    }
  }

  function report(message) {
    try {
      var text = String(message);
      var isError = text.indexOf("error:") === 0;
      var shouldReport = !!window.LYRICS_PLUS_DEBUG || isError;
      if (!shouldReport) return;
      if (window.LYRICS_PLUS_DEBUG && window.console && window.console.log) {
        window.console.log("LyricsPlus " + message);
      }
      if (window.AndroidLyrics && window.AndroidLyrics.report) {
        window.AndroidLyrics.report(text);
      }
    } catch (ignore) {
    }
  }

  function hashColor(seed, sat, light) {
    var hash = 0;
    for (var i = 0; i < seed.length; i += 1) {
      hash = (hash * 31 + seed.charCodeAt(i)) >>> 0;
    }
    return "hsl(" + (hash % 360) + ", " + sat + "%, " + light + "%)";
  }

  function escapeHtml(value) {
    return String(value == null ? "" : value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function setTrack(track) {
    try {
      var seed = String((track && track.track) || "") + String((track && track.artist) || "");
      var startColor = (track && track.backgroundStart) || hashColor(seed || "lyrics", 68, 48);
      var endColor = (track && track.backgroundEnd) || hashColor(seed + " deep", 42, 24);
      var accentColor = (track && track.backgroundAccent) || hashColor(seed + " accent", 74, 62);
      state.track = track || null;
      trackTitleEl.textContent = (track && track.track) || "lyrics";
      trackArtistEl.textContent = (track && track.artist) || "选择一首歌";
      stageEl.style.setProperty("--bg-a", startColor);
      stageEl.style.setProperty("--bg-b", endColor);
      stageEl.style.setProperty("--bg-c", accentColor);
      stageEl.setAttribute("data-track-key", seed);
      report("track: " + seed + ", colors: " + startColor + " -> " + endColor);
    } catch (error) {
      report("error:设置歌曲信息失败:" + error.message);
    }
  }

  function setLyrics(lines) {
    try {
      finishCueTouch(true);
      state.lyrics = Object.prototype.toString.call(lines) === "[object Array]" ? lines : [];
      if (state.lyrics.length > 0) {
        var firstLineStart = Number(state.lyrics[0].startTimeMs || 0);
        if (firstLineStart > 2000) {
          state.lyrics.unshift({
            startTimeMs: 0,
            text: "•••",
            translation: "",
            reading: ""
          });
        }
      }
      emptyEl.hidden = state.lyrics.length > 0;
      if (state.lyrics.length > 0) {
        var elapsed = playback.isPlaying ? (performance.now() - playback.updatedAt) : 0;
        var currentPosition = playback.positionMs + elapsed;
        state.activeIndex = findActiveIndex(currentPosition);
      } else {
        state.activeIndex = -1;
      }
      // Reset scroll position when new lyrics arrive (e.g. track change)
      if (state.isFullLyricsMode) {
        getScrollContainer().scrollTop = 0;
      }
      prevActiveIndex = -1;
      // Full rebuild: new lyrics data means new DOM
      renderFull();
      report("lyrics: " + state.lyrics.length + ", active: " + state.activeIndex);
    } catch (error) {
      report("error:设置歌词失败:" + error.message);
    }
  }

  function setPlaybackState(positionMs, isPlaying, forcePosition, fromGesture) {
    // Native bridge echoes can lag by 150ms; the gesture owns the visual clock until it ends.
    if (!fromGesture && (cueTouch && cueTouch.scrubbing || cueMotion)) return;
    try {
      var wasPlaying = playback.isPlaying;
      var newIsPlaying = !!isPlaying;
      var targetPos = Number(positionMs) || 0;

      if (newIsPlaying) {
        if (!wasPlaying) {
          // Transition from paused to playing: smoothly transition/decay the visual offset
          var frozenPos = playback.positionMs;
          var diff = frozenPos - targetPos;
          if (Math.abs(diff) < 1500) {
            playback.visualOffset = diff;
          } else {
            playback.visualOffset = 0;
          }
          playback.positionMs = targetPos;
          playback.resumeTime = performance.now();
        } else {
          // Already playing: periodic sync update
          playback.positionMs = targetPos;
        }
      } else {
        if (wasPlaying) {
          // Transition from playing to paused: freeze visually at the extrapolated position
          var elapsed = performance.now() - playback.updatedAt;
          var extrapolated = playback.positionMs + elapsed;
          if (!forcePosition && Math.abs(extrapolated - targetPos) < 1500) {
            playback.positionMs = extrapolated;
          } else {
            playback.positionMs = targetPos;
          }
          playback.visualOffset = 0;
        } else {
          // Already paused: ignore minor position updates (such as duplicate pause reports from Android)
          // only accept seeks (changes > 1.5s)
          var diff = Math.abs(playback.positionMs - targetPos);
          if (diff > 1500 || forcePosition) {
            playback.positionMs = targetPos;
            playback.visualOffset = 0;
          } else {
            // Keep the frozen position, ignore the minor update
          }
        }
      }

      playback.isPlaying = newIsPlaying;
      playback.updatedAt = performance.now();

      if (playback.isPlaying) {
        stageEl.classList.remove("paused");
      } else {
        stageEl.classList.add("paused");
      }
      updatePlaybackPosition();
    } catch (error) {
      report("error:同步播放状态失败:" + error.message);
    }
  }

  function setReadingMode(mode) {
    finishCueTouch(true);
    try {
      state.readingMode = Number(mode) || 0; // 0=None, 1=Romaji, 2=Furigana
      // Mode change requires DOM content changes, so do a full render
      renderFull();
      report("setReadingMode: " + state.readingMode);
    } catch (error) {
      report("error:设置注音模式失败:" + error.message);
    }
  }

  var _isRightAligned = false;

  function setRightAligned(rightAligned) {
    finishCueTouch(true);
    try {
      var changed = _isRightAligned !== !!rightAligned;
      _isRightAligned = !!rightAligned;
      if (rightAligned) {
        stageEl.classList.add("right-aligned");
      } else {
        stageEl.classList.remove("right-aligned");
      }
      // On orientation change, fully rebuild DOM so line visibility is correct
      if (changed && state.lyrics.length > 0) {
        prevActiveIndex = -1;
        // Disable transition to prevent jarring animation
        lyricsEl.style.transition = "none";
        renderFull();
        requestAnimationFrame(function () {
          requestAnimationFrame(function () {
            lyricsEl.style.transition = "";
          });
        });
      }
      report("setRightAligned: " + rightAligned);
    } catch (error) {
      report("error:设置横屏布局失败:" + error.message);
    }
  }


  function updatePlaybackPosition() {
    if (!state.lyrics.length) return;
    var elapsed = playback.isPlaying ? (performance.now() - playback.updatedAt) : 0;
    var currentPosition = playback.positionMs + elapsed;

    // Apply smooth visual offset decay if resuming
    if (playback.isPlaying && playback.visualOffset) {
      var decayTime = 1000; // Decay the visual offset to 0 over 1 second
      var timeSinceResume = performance.now() - playback.resumeTime;
      if (timeSinceResume < decayTime) {
        var ratio = 1 - (timeSinceResume / decayTime);
        ratio = ratio * ratio; // quadratic ease-out
        currentPosition += playback.visualOffset * ratio;
      } else {
        playback.visualOffset = 0;
      }
    }

    var nextIndex = findActiveIndex(currentPosition);
    if (nextIndex !== state.activeIndex) {
      state.activeIndex = nextIndex;
      if (state.isFullLyricsMode) {
        // Lightweight update: just swap CSS classes, no DOM re-render
        updateActiveClassesOnly();
      } else {
        // Incremental update: patch styles and classes, no DOM rebuild
        patchFocusedMode();
      }
      recacheActiveSyllables();
      report("playback: " + Math.round(currentPosition / 1000) + "s, active: " + state.activeIndex);
    }
    updateSyllableHighlights(currentPosition);
  }

  function updateSyllableHighlights(currentPosition) {
    var syllables = cachedActiveSyllables;
    if (!syllables.length) return;
    
    for (var i = 0; i < syllables.length; i++) {
      var item = syllables[i];
      var el = item.el;
      var start = item.start;
      var end = item.end;
      
      var targetClass = "syllable";
      var progress = 0;
      
      if (currentPosition >= end) {
        targetClass = "syllable past";
        progress = 100;
      } else if (currentPosition >= start && currentPosition < end) {
        targetClass = "syllable active";
        var duration = end - start;
        progress = duration > 0 ? Math.round(((currentPosition - start) / duration) * 100) : 100;
      } else {
        targetClass = "syllable";
        progress = 0;
      }

      // Write class name only when it changes
      if (item.lastClass !== targetClass) {
        el.className = targetClass;
        item.lastClass = targetClass;
      }

      // Write CSS property only when progress changes
      if (item.lastProgress !== progress) {
        el.style.setProperty("--progress", progress + "%");
        item.lastProgress = progress;
      }
    }
  }

  function setInactiveLineSyllables(lineEl, completed) {
    var syllables = lineEl.querySelectorAll(".syllable");
    if (!syllables.length) return;

    var targetClass = completed ? "syllable past" : "syllable";
    var progress = completed ? "100%" : "0%";
    for (var i = 0; i < syllables.length; i += 1) {
      var syllable = syllables[i];
      if (syllable.className !== targetClass) {
        syllable.className = targetClass;
      }
      if (syllable.style.getPropertyValue("--progress") !== progress) {
        syllable.style.setProperty("--progress", progress);
      }
    }
  }

  var vrrToggle = false;
  function tick() {
    if (playback.isPlaying || cueTouch && cueTouch.scrubbing || cueMotion) {
      if (playback.isPlaying) updatePlaybackPosition();
      // Force 120Hz compositor scheduling on VRR Android screens.
      // Two signals are needed to convince the WebView compositor every frame has work:
      //   1. transform (compositor-only, no layout/paint) — matched by will-change:transform
      //   2. opacity micro-alternation (pixel-level change) — forces actual pixel diff
      // Together they prevent the WebView from throttling RAF to 60Hz.
      if (vrrKeepaliveEl) {
        vrrToggle = !vrrToggle;
        vrrKeepaliveEl.style.transform = vrrToggle ? "translateX(0.5px)" : "translateX(0px)";
        vrrKeepaliveEl.style.opacity = vrrToggle ? "0.015" : "0.01";
      }
    }
    requestAnimationFrame(tick);
  }
  requestAnimationFrame(tick);

  function findActiveIndex(positionMs) {
    var lyrics = state.lyrics;
    var len = lyrics.length;
    if (len === 0) return -1;
    if (positionMs < Number(lyrics[0].startTimeMs || 0)) return 0;

    var low = 0;
    var high = len - 1;
    var result = 0;

    while (low <= high) {
      var mid = (low + high) >> 1;
      var midTime = Number(lyrics[mid].startTimeMs || 0);

      if (positionMs >= midTime) {
        result = mid;
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    return result;
  }

  function clampedActiveIndex() {
    if (!state.lyrics.length) return -1;
    if (state.activeIndex < 0) return 0;
    if (state.activeIndex >= state.lyrics.length) return state.lyrics.length - 1;
    return state.activeIndex;
  }

  // ---------- Focused-mode helpers ----------

  function getDistanceStyle(distance) {
    // Past lines: fully hidden (no blur bar needed)
    if (distance < 0) {
      return "opacity:0;transform:scale(0.78)";
    }
    var scale = distance === 0 ? 1 : Math.abs(distance) === 1 ? 0.88 : 0.78;
    var opacity = distance === 0 ? 1 : Math.abs(distance) === 1 ? 0.55 : 0.25;
    return "opacity:" + opacity + ";transform:scale(" + scale + ")";
  }

  function getKindClass(distance) {
    if (distance === 0) return "active";
    if (Math.abs(distance) === 1) return "near";
    return "far";
  }

  function getDirectionClass(distance) {
    if (distance < 0) return "past";
    if (distance > 0) return "future";
    return "";
  }

  function getLengthClass(line) {
    var textLen = (line.text || "").length;
    var transLen = (line.translation || "").length;
    var totalLen = textLen + transLen * 0.7;
    return totalLen > 65 ? "long" : totalLen < 12 ? "short" : "";
  }

  function parseSyllables(lineText, lineStartTime) {
    var syllables = [];
    lineText = lineText || "";
    lineStartTime = Number(lineStartTime) || 0;

    // 1. Enhanced LRC: <00:12.34>word
    var lrcRegex = /<(\d{2}):(\d{2})[.:](\d{2,3})>([^<]*)/g;

    // 2. NetEase YRC prefix or QRC absolute: (12580,250,0)word or (19538,207)word
    var prefixRegex = /\((\d+),(\d+)(?:,\d+)?\)([^(<]+)/g;

    // 3. QQ Music YRC/QRC suffix: word(293,293) or word (19538,207)
    // NOTE: We exclude ')' and '(' and '<' from the character class to prevent matching timing tag boundaries.
    var suffixRegex = /([^(<)]+)\s*\((\d+),(\d+)\)/g;

    var match;

    if (lrcRegex.test(lineText)) {
      lrcRegex.lastIndex = 0;
      while ((match = lrcRegex.exec(lineText)) !== null) {
        var min = parseInt(match[1], 10);
        var sec = parseInt(match[2], 10);
        var fracStr = match[3];
        var text = match[4];

        var frac = parseInt(fracStr, 10);
        if (fracStr.length === 2) frac *= 10;

        var timeMs = min * 60000 + sec * 1000 + frac;
        syllables.push({
          timeMs: timeMs,
          text: text
        });
      }
    } else {
      // Robust format detection: if the line starts with a parenthesized timing tag, e.g. (12580,250), it is prefix format.
      // Otherwise, it is suffix format (QQ Music standard YRC/QRC).
      var isPrefix = /^\s*\(\d+,\d+(?:,\d+)?\)/.test(lineText);
      if (isPrefix) {
        prefixRegex.lastIndex = 0;
        while ((match = prefixRegex.exec(lineText)) !== null) {
          var startMs = parseInt(match[1], 10);
          var durationMs = parseInt(match[2], 10);
          var text = match[3];

          // Resolve absolute vs relative (bulletproof check to prevent minor QRC timestamp offsets from being misidentified as relative)
          var isRelative = (startMs < lineStartTime) && (startMs < 1000 || (lineStartTime - startMs) > Math.max(2000, lineStartTime * 0.5));
          var timeMs = isRelative ? (lineStartTime + startMs) : startMs;
          syllables.push({
            timeMs: timeMs,
            durationMs: durationMs,
            text: text
          });
        }
      } else {
        suffixRegex.lastIndex = 0;
        while ((match = suffixRegex.exec(lineText)) !== null) {
          var text = match[1];
          var startMs = parseInt(match[2], 10);
          var durationMs = parseInt(match[3], 10);

          // Resolve absolute vs relative (bulletproof check to prevent minor QRC timestamp offsets from being misidentified as relative)
          var isRelative = (startMs < lineStartTime) && (startMs < 1000 || (lineStartTime - startMs) > Math.max(2000, lineStartTime * 0.5));
          var timeMs = isRelative ? (lineStartTime + startMs) : startMs;
          syllables.push({
            timeMs: timeMs,
            durationMs: durationMs,
            text: text
          });
        }
      }
    }

    return syllables;
  }

  function stripSyllableTimestamps(lineText) {
    if (!lineText) return "";
    return lineText
      .replace(/<(\d{2}):(\d{2})[.:](\d{2,3})>/g, "")
      .replace(/\(\d+,\d+(?:,\d+)?\)/g, "");
  }

  function buildFuriganaInnerHTML(furiganaHtml, syllables, lineDuration, lineStartTime, isPast) {
    if (!furiganaHtml) return "";
    var tokens = furiganaHtml.match(/<ruby>.*?<\/ruby>|[^<>]/g) || [];
    if (syllables.length === 0) return furiganaHtml;

    var html = "";
    var className = isPast ? "syllable past" : "syllable";
    var progressStyle = isPast ? ' style="--progress: 100%"' : '';

    for (var i = 0; i < tokens.length; i++) {
      var sIdx = Math.floor(i * syllables.length / tokens.length);
      if (sIdx >= syllables.length) sIdx = syllables.length - 1;

      var s = syllables[sIdx];

      var nextSIdx = Math.floor((i + 1) * syllables.length / tokens.length);
      if (nextSIdx >= syllables.length) nextSIdx = syllables.length;
      var nextS = syllables[nextSIdx];

      var sEnd = s.durationMs ? (s.timeMs + s.durationMs) : (nextS ? nextS.timeMs : (lineStartTime + lineDuration));
      if (!s.durationMs && !nextS) {
        sEnd = s.timeMs + Math.min(lineStartTime + lineDuration - s.timeMs, 1000);
      }
      if (sEnd <= s.timeMs) {
        sEnd = s.timeMs + 200;
      }

      html += '<span class="' + className + '" data-start="' + s.timeMs + '" data-end="' + sEnd + '"' + progressStyle + '>' + tokens[i] + '</span>';
    }
    return html;
  }

  function buildRomajiInnerHTML(romajiText, lineStartTime, nextLine, mainSyllables, isPast) {
    if (!romajiText) return "";
    // If the Romaji text only contains timing tags and no actual letters/words, discard it
    var clean = romajiText.replace(/\(\d+,\d+(?:,\d+)?\)/g, "").trim();
    if (!clean) return "";

    var romajiSyllables = parseSyllables(romajiText, lineStartTime);
    var html = "";
    var className = isPast ? "syllable past" : "syllable";
    var progressStyle = isPast ? ' style="--progress: 100%"' : '';

    if (romajiSyllables.length > 0) {
      var lineDuration = nextLine ? (nextLine.startTimeMs - lineStartTime) : 4000;
      for (var sIdx = 0; sIdx < romajiSyllables.length; sIdx++) {
        var s = romajiSyllables[sIdx];
        var nextS = romajiSyllables[sIdx + 1];
        var sEnd = s.durationMs ? (s.timeMs + s.durationMs) : (nextS ? nextS.timeMs : (lineStartTime + lineDuration));
        if (!s.durationMs && !nextS) {
          sEnd = s.timeMs + Math.min(lineStartTime + lineDuration - s.timeMs, 1000);
        }
        if (sEnd <= s.timeMs) {
          sEnd = s.timeMs + 200;
        }
        var spacer = sIdx === romajiSyllables.length - 1 ? "" : " ";
        html += '<span class="' + className + '" data-start="' + s.timeMs + '" data-end="' + sEnd + '"' + progressStyle + '>' + escapeHtml(s.text) + '</span>' + spacer;
      }
    } else if (mainSyllables && mainSyllables.length > 0) {
      var tokens = romajiText.trim().split(/\s+/);
      var lineDuration = nextLine ? (nextLine.startTimeMs - lineStartTime) : 4000;
      for (var i = 0; i < tokens.length; i++) {
        var sIdx = Math.floor(i * mainSyllables.length / tokens.length);
        if (sIdx >= mainSyllables.length) sIdx = mainSyllables.length - 1;
        var s = mainSyllables[sIdx];

        var nextSIdx = Math.floor((i + 1) * mainSyllables.length / tokens.length);
        if (nextSIdx >= mainSyllables.length) nextSIdx = mainSyllables.length;
        var nextS = mainSyllables[nextSIdx];

        var sEnd = s.durationMs ? (s.timeMs + s.durationMs) : (nextS ? nextS.timeMs : (lineStartTime + lineDuration));
        if (!s.durationMs && !nextS) {
          sEnd = s.timeMs + Math.min(lineStartTime + lineDuration - s.timeMs, 1000);
        }
        if (sEnd <= s.timeMs) {
          sEnd = s.timeMs + 200;
        }

        var spacer = i === tokens.length - 1 ? "" : " ";
        html += '<span class="' + className + '" data-start="' + s.timeMs + '" data-end="' + sEnd + '"' + progressStyle + '>' + escapeHtml(tokens[i]) + '</span>' + spacer;
      }
    } else {
      html = escapeHtml(romajiText);
    }
    return html;
  }

  function buildLineInnerHTML(line, isActive, nextLine, isPast) {
    var parsedReading = null;
    if (line.reading) {
      try {
        if (line.reading.startsWith("{")) {
          parsedReading = JSON.parse(line.reading);
        }
      } catch (e) {}
    }

    var romajiText = "";
    var furiganaHtml = "";
    if (parsedReading) {
      romajiText = parsedReading.romaji || "";
      furiganaHtml = parsedReading.furigana || "";
    } else if (line.reading) {
      romajiText = line.reading;
    }

    var syllables = parseSyllables(line.text || "", Number(line.startTimeMs || 0));
    var lineTextHtml = "";
    var lineDuration = nextLine ? (nextLine.startTimeMs - line.startTimeMs) : 4000;

    var showFurigana = (state.readingMode === 2 && furiganaHtml && (state.isFullLyricsMode || isActive));

    if (showFurigana) {
      // 1. Furigana mode: Use raw HTML containing <ruby> tags (with word-level sweeps if matched)
      lineTextHtml = buildFuriganaInnerHTML(furiganaHtml, syllables, lineDuration, Number(line.startTimeMs || 0), isPast);
    } else if (syllables.length > 0) {
      // 2. Karaoke mode: Syllable-level enhanced LRC highlights
      var className = isPast ? "syllable past" : "syllable";
      var progressStyle = isPast ? ' style="--progress: 100%"' : '';
      for (var sIdx = 0; sIdx < syllables.length; sIdx++) {
        var s = syllables[sIdx];
        var nextS = syllables[sIdx + 1];
        var sEnd = s.durationMs ? (s.timeMs + s.durationMs) : (nextS ? nextS.timeMs : (line.startTimeMs + lineDuration));
        if (!s.durationMs && !nextS) {
          sEnd = s.timeMs + Math.min(line.startTimeMs + lineDuration - s.timeMs, 1000);
        }
        if (sEnd <= s.timeMs) {
          sEnd = s.timeMs + 200;
        }
        lineTextHtml += '<span class="' + className + '" data-start="' + s.timeMs + '" data-end="' + sEnd + '"' + progressStyle + '>' + escapeHtml(s.text) + '</span>';
      }
    } else {
      // 3. Standard line-level text
      lineTextHtml = escapeHtml(line.text || "♪");
      if (line.text === "•••") {
        if (isActive) {
          lineTextHtml = '<span class="intro-dots"><span class="dot dot-1">•</span><span class="dot dot-2">•</span><span class="dot dot-3">•</span></span>';
        } else {
          lineTextHtml = '•••';
        }
      }
    }

    var readingHtml = "";
    if (state.readingMode === 1 && romajiText) {
      if (state.isFullLyricsMode || isActive) {
        readingHtml = '<span class="romaji">' + buildRomajiInnerHTML(romajiText, Number(line.startTimeMs || 0), nextLine, syllables, isPast) + "</span>";
      }
    }

    var translation = line.translation ? '<span class="translation">' + escapeHtml(line.translation) + "</span>" : "";

    return readingHtml + '<span class="text">' + lineTextHtml + "</span>" + translation;
  }

  /**
   * Incremental patch for focused (default) mode.
   * Only updates CSS classes, inline styles, and romaji/dot content where needed.
   * Does NOT destroy/recreate DOM nodes.
   */
  function patchFocusedMode() {
    var active = clampedActiveIndex();
    var prevActive = prevActiveIndex;
    prevActiveIndex = active;
    state.activeIndex = active;

    var lines = lyricsEl.querySelectorAll(".line");
    if (!lines.length) return;

    // A finger crossing one line is still an incremental change, not a whole-song seek.
    var isSeek = prevActive === -1 || Math.abs(active - prevActive) > 3;
    var totalLines = lines.length;

    // Narrow the iteration range: only update lines whose visual state actually changed
    var loopStart, loopEnd;
    if (isSeek) {
      loopStart = 0;
      loopEnd = totalLines - 1;
    } else {
      // Only lines near prevActive and active need updates
      loopStart = Math.max(0, Math.min(prevActive, active) - 2);
      loopEnd = Math.min(totalLines - 1, Math.max(prevActive, active) + 2);
    }

    var hasReadingMode = state.readingMode > 0;

    for (var i = loopStart; i <= loopEnd; i += 1) {
      var distance = i - active;

      if (!isSeek) {
        var prevDistance = i - prevActive;
        // Skip elements that didn't change state
        if ((prevDistance > 1 && distance > 1) || (prevDistance < 0 && distance < 0)) {
          continue;
        }
      }

      var el = lines[i];
      var kind = getKindClass(distance);
      var direction = getDirectionClass(distance);

      // Update classes
      el.classList.remove("active", "near", "far", "past", "future");
      el.classList.add(kind);
      if (direction) el.classList.add(direction);

      // Update inline styles (these will transition via CSS)
      el.setAttribute("style", getDistanceStyle(distance));

      // Update dots innerHTML only for the "•••" intro line
      var lineData = state.lyrics[i];
      if (lineData && lineData.text === "•••") {
        var textSpan = el.querySelector(".text");
        if (textSpan) {
          if (distance === 0) {
            textSpan.innerHTML = '<span class="intro-dots"><span class="dot dot-1">•</span><span class="dot dot-2">•</span><span class="dot dot-3">•</span></span>';
          } else {
            textSpan.textContent = '•••';
          }
        }
      }

      // Skip expensive reading/furigana processing if readingMode is off or line has no reading data
      if (prevActive >= 0 && i !== active && i !== prevActive) continue;
      if (!hasReadingMode || !lineData || !lineData.reading) {
        // Still remove stale romaji spans if reading mode was just turned off
        if (!hasReadingMode) {
          var existingRomaji = el.querySelector(".romaji");
          if (existingRomaji) existingRomaji.remove();
        }
        continue;
      }

      // --- Reading data processing (only reached when readingMode > 0 AND lineData.reading exists) ---
      var parsedReading = null;
      if (lineData.reading) {
        try {
          if (lineData.reading.startsWith("{")) {
            parsedReading = JSON.parse(lineData.reading);
          }
        } catch (e) {}
      }
      var romajiText = "";
      if (parsedReading) {
        romajiText = parsedReading.romaji || "";
      } else if (lineData && lineData.reading) {
        romajiText = lineData.reading;
      }

      var existingRomaji = el.querySelector(".romaji");
      if (state.readingMode === 1 && romajiText) {
        var nextLineData = state.lyrics[i + 1];
        var mainSyllables = parseSyllables(lineData.text || "", Number(lineData.startTimeMs || 0));
        var romajiHtml = buildRomajiInnerHTML(romajiText, Number(lineData.startTimeMs || 0), nextLineData, mainSyllables);
        if (distance === 0) {
          if (!existingRomaji) {
            var romajiSpan = document.createElement("span");
            romajiSpan.className = "romaji";
            romajiSpan.innerHTML = romajiHtml;
            el.insertBefore(romajiSpan, el.firstChild);
          } else {
            existingRomaji.innerHTML = romajiHtml;
          }
        } else {
          if (existingRomaji) {
            existingRomaji.remove();
          }
        }
      } else {
        if (existingRomaji) {
          existingRomaji.remove();
        }
      }

      // Update Furigana annotations dynamically in focused mode (show only on active line)
      var textSpan = el.querySelector(".text");
      if (textSpan && state.readingMode === 2 && lineData) {
        var furiganaHtml = parsedReading ? (parsedReading.furigana || "") : "";
        if (furiganaHtml) {
          var mainSyllables = parseSyllables(lineData.text || "", Number(lineData.startTimeMs || 0));
          var lineDuration = (state.lyrics[i + 1]) ? (state.lyrics[i + 1].startTimeMs - lineData.startTimeMs) : 4000;
          
          if (distance === 0) {
            // Active line: show Furigana (annotated Kanji with ruby tags)
            var activeHtml = buildFuriganaInnerHTML(furiganaHtml, mainSyllables, lineDuration, Number(lineData.startTimeMs || 0));
            if (textSpan.innerHTML !== activeHtml) {
              textSpan.innerHTML = activeHtml;
            }
          } else {
            // Inactive line: show clean original text (Kanji without ruby tags)
            var cleanHtml = "";
            if (mainSyllables.length > 0) {
              for (var sIdx = 0; sIdx < mainSyllables.length; sIdx++) {
                var s = mainSyllables[sIdx];
                var nextS = mainSyllables[sIdx + 1];
                var sEnd = s.durationMs ? (s.timeMs + s.durationMs) : (nextS ? nextS.timeMs : (lineData.startTimeMs + lineDuration));
                if (!s.durationMs && !nextS) {
                  sEnd = s.timeMs + Math.min(lineData.startTimeMs + lineDuration - s.timeMs, 1000);
                }
                if (sEnd <= s.timeMs) {
                  sEnd = s.timeMs + 200;
                }
                cleanHtml += '<span class="syllable" data-start="' + s.timeMs + '" data-end="' + sEnd + '">' + escapeHtml(s.text) + '</span>';
              }
            } else {
              cleanHtml = escapeHtml(lineData.text || "♪");
            }
            if (textSpan.innerHTML !== cleanHtml) {
              textSpan.innerHTML = cleanHtml;
            }
          }
        }
      }
    }

    // Query the current row in the frame, never a detached/stale row snapshot.
    var generation = layoutGeneration;
    requestAnimationFrame(function () {
      if (generation !== layoutGeneration || state.isFullLyricsMode || cueTouch && cueTouch.scrubbing || cueMotion) return;
      alignFocusedRow();
    });
  }

  function alignFocusedRow() {
    if (state.isFullLyricsMode) return;
    stageEl.scrollTop = 0;
    getViewport().scrollTop = 0;
    var activeLine = lyricsEl.querySelector('.line.active');
    lyricsEl.style.transform = _isRightAligned || !activeLine ? "none" : "translateY(-" + activeLine.offsetTop + "px)";
    fitFocusedFontSize();
  }

  function getViewport() { return document.querySelector('.lyrics-viewport') || stageEl; }

  /**
   * Full render – builds or rebuilds all DOM nodes.
   * Called on initial load, lyrics change, romaji toggle, or mode switch.
   */
  function renderFull() {
    var generation = ++layoutGeneration;
    if (!state.lyrics.length) {
      lyricsEl.innerHTML = "";
      emptyEl.hidden = false;
      return;
    }

    var active = clampedActiveIndex();
    state.activeIndex = active;

    var html = "";
    for (var i = 0; i < state.lyrics.length; i += 1) {
      var line = state.lyrics[i] || {};
      var distance = i - active;
      var lengthClass = getLengthClass(line);
      var kind = getKindClass(distance);
      var direction = getDirectionClass(distance);
      var timingClass = parseSyllables(line.text || "", Number(line.startTimeMs || 0)).length > 0
        ? "word-synced"
        : "line-synced";

      var isActive = distance === 0;

      if (state.isFullLyricsMode) {
        html += '<article class="line ' + kind + " " + direction + " " + lengthClass + " " + timingClass + '"' +
          ' data-index="' + i + '">' +
          buildLineInnerHTML(line, isActive, state.lyrics[i + 1], distance < 0) +
          "</article>";
      } else {
        html += '<article class="line ' + kind + " " + direction + " " + lengthClass + " " + timingClass + '"' +
          ' data-index="' + i + '"' +
          ' style="' + getDistanceStyle(distance) + '">' +
          buildLineInnerHTML(line, isActive, state.lyrics[i + 1], distance < 0) +
          "</article>";
      }
    }

    lyricsEl.innerHTML = html;

    requestAnimationFrame(function () {
      if (generation !== layoutGeneration) return;
      var activeLine = lyricsEl.querySelector('.line.active');
      if (state.isFullLyricsMode) {
        lyricsEl.style.transform = "none";
        if (activeLine) scrollToActiveIfNeeded(activeLine);
      } else alignFocusedRow();
      recacheActiveSyllables();
      updatePlaybackPosition();
    });

    report("render: active=" + active + " total=" + state.lyrics.length);
  }

  // Lightweight active-line update for full-lyrics-mode: swap classes without re-rendering DOM
  function updateActiveClassesOnly() {
    var active = clampedActiveIndex();
    var lines = lyricsEl.querySelectorAll(".line");
    if (!lines.length) return;

    for (var i = 0; i < lines.length; i += 1) {
      var el = lines[i];
      var distance = i - active;
      var wasPast = el.classList.contains("past");
      var wasFuture = el.classList.contains("future");
      // Remove old state classes
      el.classList.remove("active", "near", "far", "past", "future");
      // Add new state classes
      if (distance === 0) {
        el.classList.add("active");
      } else if (Math.abs(distance) === 1) {
        el.classList.add("near");
      } else {
        el.classList.add("far");
      }
      if (distance < 0) {
        el.classList.add("past");
        if (!wasPast) {
          // RAF is suspended while the app is backgrounded, so playback can
          // skip whole lines. Complete every syllable that just became past.
          setInactiveLineSyllables(el, true);
        }
      } else if (distance > 0) {
        el.classList.add("future");
        if (!wasFuture) {
          // A backward seek can move completed lines into the future again.
          setInactiveLineSyllables(el, false);
        }
      }
    }

    // Auto-scroll only if active line is out of viewport
    if (lines[active]) {
      scrollToActiveIfNeeded(lines[active]);
    }
  }

  // ---------- Dynamic font sizing for focused right-aligned mode ----------
  // Binary-searches for the largest text font-size that makes
  // romaji + text + translation fit within the available content area.
  function fitFocusedFontSize() {
    if (!_isRightAligned || state.isFullLyricsMode) return;
    var activeLine = lyricsEl.querySelector(".line.active");
    if (!activeLine) return;

    // clientHeight includes padding; subtract it to get the actual safe content area
    var cs = getComputedStyle(lyricsEl);
    var padTop = parseFloat(cs.paddingTop) || 0;
    var padBottom = parseFloat(cs.paddingBottom) || 0;
    var available = lyricsEl.clientHeight - padTop - padBottom;
    if (available <= 0) available = window.innerHeight * 0.8;
    var target = available * 0.95;

    var lo = 12, hi = 60;
    var bestBase = lo;

    // Quick binary search (6 iterations → precision < 1px)
    for (var iter = 0; iter < 6; iter++) {
      var mid = (lo + hi) / 2;
      applyFocusedSizes(mid);
      var h = activeLine.scrollHeight;
      if (h <= target) {
        bestBase = mid;
        lo = mid;
      } else {
        hi = mid;
      }
    }

    applyFocusedSizes(bestBase);
    report("fitFocusedFontSize: base=" + Math.round(bestBase) + "px, available=" + Math.round(available) + ", pad=" + Math.round(padTop));
  }

  function applyFocusedSizes(base) {
    var textPx = Math.round(base) + "px";
    var transPx = Math.round(base * 0.62) + "px";
    var romajiPx = Math.round(base * 0.42) + "px";
    stageEl.style.setProperty("--focused-text-size", textPx);
    stageEl.style.setProperty("--focused-trans-size", transPx);
    stageEl.style.setProperty("--focused-romaji-size", romajiPx);
  }

  function setInAppFontScale(scale) {
    finishCueTouch(true);
    try {
      var s = Number(scale) || 1.0;
      stageEl.style.setProperty('--in-app-font-scale', s);
      renderFull();
    } catch (error) {
      report("error:设置字号失败:" + error.message);
    }
  }

  window.LyricsPlus = {
    setTrack: setTrack,
    setLyrics: setLyrics,
    setPlaybackState: setPlaybackState,
    setReadingMode: setReadingMode,
    setRightAligned: setRightAligned,
    setInAppFontScale: setInAppFontScale,
    toggleMode: toggleFullLyricsMode
  };

  window.onerror = function (message, source, line) {
    report("error:" + message + "@" + line);
  };

  // ---------- Full-lyrics-mode: scroll-pause on user interaction ----------
  // When the user manually scrolls in full-lyrics-mode, we suppress auto-scroll
  // to the active line. After a period of no interaction, we resume auto-scroll.

  var SCROLL_RESUME_DELAY_MS = 3000; // 3 seconds after last touch → resume auto-scroll
  var userScrolling = false;
  var scrollResumeTimer = null;

  function onUserScrollInteraction() {
    if (!state.isFullLyricsMode || (cueTouch && cueTouch.scrubbing)) return;
    userScrolling = true;
    // Reset the resume timer
    if (scrollResumeTimer !== null) {
      clearTimeout(scrollResumeTimer);
    }
    scrollResumeTimer = setTimeout(function () {
      userScrolling = false;
      scrollResumeTimer = null;
      // Resume: immediately scroll to the current active line
      var lines = lyricsEl.querySelectorAll(".line");
      var active = clampedActiveIndex();
      if (lines[active]) {
        scrollToActiveIfNeeded(lines[active]);
      }
    }, SCROLL_RESUME_DELAY_MS);
  }

  // Listen for user-initiated scroll/touch on both possible scroll containers
  var viewportEl = document.querySelector(".lyrics-viewport");
  stageEl.addEventListener("touchstart", onUserScrollInteraction, { passive: true });
  stageEl.addEventListener("touchmove", onUserScrollInteraction, { passive: true });
  viewportEl.addEventListener("touchstart", onUserScrollInteraction, { passive: true });
  viewportEl.addEventListener("touchmove", onUserScrollInteraction, { passive: true });

  // Scroll active line — but only if the user isn't manually browsing
  function scrollToActiveIfNeeded(activeLine) {
    if (!state.isFullLyricsMode) return;
    if (userScrolling) return; // User is browsing, don't fight their scroll
    getScrollContainer().scrollTo({
      top: activeLine.offsetTop - getScrollContainer().offsetHeight * 0.05,
      behavior: "smooth"
    });
  }

  window.LyricsPlus = {
    setTrack: setTrack,
    setLyrics: setLyrics,
    setPlaybackState: setPlaybackState,
    setReadingMode: setReadingMode,
    setRightAligned: setRightAligned,
    setInAppFontScale: setInAppFontScale,
    toggleMode: toggleFullLyricsMode
  };

  window.onerror = function (message, source, line) {
    report("error:" + message + "@" + line);
  };

  // Continuous spatial seeking. Row height maps to timed syllables, not just line starts.
  var cueTouch = null;
  var cueMotion = null;
  var cueMotionFrame = null;
  var cueDragFrame = null;
  var cueSettleTimer = null;
  var cueHoldTimer = null;
  var suppressClickUntil = 0;
  var cueGuide = document.createElement("div");
  cueGuide.className = "cue-guide";
  cueGuide.hidden = true;
  cueGuide.innerHTML = '<span class="cue-guide-time"></span>';
  document.body.appendChild(cueGuide);

  function cueBridge(method, value) {
    if (window.AndroidLyrics && typeof window.AndroidLyrics[method] === "function") {
      if (value === undefined) window.AndroidLyrics[method]();
      else window.AndroidLyrics[method](value);
    }
  }

  function clearCueHold() {
    if (cueHoldTimer !== null) clearTimeout(cueHoldTimer);
    cueHoldTimer = null;
  }

  function cueTiming(index) {
    var line = state.lyrics[index];
    var start = Number(line.startTimeMs) || 0;
    var next = state.lyrics[index + 1];
    var limit = next ? Number(next.startTimeMs) : Math.max(start + 8000, Number(state.track && state.track.durationSeconds) * 1000 || 0);
    var syllables = parseSyllables(line.text, start).filter(function (s) { return s.timeMs >= start && s.timeMs < limit; });
    if (!syllables.length) return [{ phase: 0, time: start }, { phase: 1, time: limit }];
    var total = syllables.reduce(function (sum, s) { return sum + Math.max(1, Array.from(s.text.trim()).length); }, 0);
    var weight = 0;
    var points = syllables.map(function (syllable, i) {
      var point = { phase: weight / total, time: i === 0 ? start : syllable.timeMs };
      weight += Math.max(1, Array.from(syllable.text.trim()).length);
      return point;
    });
    var last = syllables[syllables.length - 1];
    var end = last.durationMs ? last.timeMs + last.durationMs : limit;
    points.push({ phase: 1, time: Math.max(start + 1, Math.min(limit, end)) });
    return points;
  }

  function phaseToCueTime(index, phase) {
    var points = cueTiming(index);
    phase = Math.max(0, Math.min(1, phase));
    for (var i = 1; i < points.length; i++) {
      if (phase <= points[i].phase) {
        var fraction = (phase - points[i - 1].phase) / Math.max(.0001, points[i].phase - points[i - 1].phase);
        return points[i - 1].time + fraction * (points[i].time - points[i - 1].time);
      }
    }
    return points[points.length - 1].time;
  }

  function cueTimeToPhase(index, position) {
    var points = cueTiming(index);
    for (var i = 1; i < points.length; i++) {
      if (position <= points[i].time) {
        var fraction = Math.max(0, Math.min(1, (position - points[i - 1].time) / Math.max(1, points[i].time - points[i - 1].time)));
        return points[i - 1].phase + fraction * (points[i].phase - points[i - 1].phase);
      }
    }
    return 1;
  }

  function showCueTime(position) {
    var tenths = Math.floor(position / 100);
    cueGuide.firstChild.textContent = Math.floor(tenths / 600) + ":" + ("0" + Math.floor(tenths / 10) % 60).slice(-2) + "." + tenths % 10;
  }

  function cueSeek(position, index, phase) {
    position = Math.max(0, Math.round(position));
    setPlaybackState(position, false, true, true);
    if (clampedActiveIndex() !== index) phase = cueTimeToPhase(clampedActiveIndex(), position);
    if (!state.isFullLyricsMode) {
      var activeLine = lyricsEl.querySelector('.line.active');
      var context = cueTouch || cueMotion;
      if (activeLine) {
        if (!_isRightAligned) {
          var within = context && !context.fine ? phase * activeLine.offsetHeight - context.initialWithin : 0;
          lyricsEl.style.transform = "translateY(-" + (activeLine.offsetTop + within) + "px)";
        }
        if (context && context.fine) {
          var rect = activeLine.getBoundingClientRect();
          cueGuide.style.top = Math.max(32, Math.min(window.innerHeight - 40, rect.top + rect.height * phase)) + "px";
        }
      }
    }
    cueBridge("seekCue", position);
    showCueTime(position);
  }

  function cueSeekAtHeight(target) {
    var context = cueTouch || cueMotion;
    if (!context) return;
    var rows = context.rows;
    var index = 0;
    for (var i = 1; i < rows.length; i++) {
      if (target >= rows[i].top) index = i;
      else break;
    }
    var row = rows[index];
    var phase = Math.max(0, Math.min(1, (target - row.top) / Math.max(1, row.height)));
    var position = phaseToCueTime(index, phase);
    if (phase === 1 && rows[index + 1]) {
      var gap = rows[index + 1].top - row.top - row.height;
      if (gap > 0) {
        var gapFraction = Math.max(0, Math.min(1, (target - row.top - row.height) / gap));
        position += gapFraction * (Number(state.lyrics[index + 1].startTimeMs) - position);
      }
    }
    context.target = target;
    cueSeek(position, index, phase);
  }

  function beginCueTouch(pressedIndex, fine) {
    if (!cueTouch || !state.lyrics.length || cueTouch.scrubbing) return;
    clearCueHold();
    cueTouch.scrubbing = true;
    cueTouch.fine = !!fine;
    suppressClickUntil = Date.now() + 1000;
    var lines = lyricsEl.querySelectorAll(".line");
    var initialIndex = state.isFullLyricsMode ? pressedIndex : clampedActiveIndex();
    var rect = lines[initialIndex].getBoundingClientRect();
    var rowStep = Math.max(120, rect.height + 32);
    cueTouch.rows = Array.prototype.map.call(lines, function (line, index) {
      var virtual = _isRightAligned && !state.isFullLyricsMode;
      return { top: virtual ? index * rowStep : line.offsetTop, height: virtual ? rect.height : line.offsetHeight };
    });
    var position = playback.positionMs + (playback.isPlaying ? performance.now() - playback.updatedAt : 0);
    var phase = state.isFullLyricsMode ? Math.max(0, Math.min(1, (cueTouch.y - rect.top) / rect.height)) : cueTimeToPhase(initialIndex, position);
    cueTouch.anchorOffset = cueTouch.rows[initialIndex].top + phase * cueTouch.rows[initialIndex].height;
    cueTouch.initialWithin = phase * rect.height;
    cueTouch.scrollTop = getScrollContainer().scrollTop;
    cueGuide.style.top = Math.max(32, Math.min(window.innerHeight - 40, cueTouch.y)) + "px";
    stageEl.classList.add(fine ? "cue-scrubbing" : "cue-dragging");
    cueGuide.hidden = !fine;
    userScrolling = true;
    if (scrollResumeTimer !== null) clearTimeout(scrollResumeTimer);
    scrollResumeTimer = null;
    cueBridge("beginScrub");
    if (state.isFullLyricsMode) cueSeekAtHeight(cueTouch.anchorOffset);
    else cueSeek(position, initialIndex, phase);
  }

  function finishCueMotion() {
    if (cueMotionFrame !== null) cancelAnimationFrame(cueMotionFrame);
    cueMotionFrame = null;
    if (!cueMotion) return;
    cueMotion = null;
    completeCueGesture();
  }

  function completeCueGesture() {
    stageEl.classList.remove("cue-scrubbing", "cue-dragging");
    cueGuide.hidden = true;
    cueBridge("endScrub");
    if (state.isFullLyricsMode) onUserScrollInteraction();
    else {
      userScrolling = false;
      stageEl.classList.add("cue-settling");
      alignFocusedRow();
      if (cueSettleTimer !== null) clearTimeout(cueSettleTimer);
      cueSettleTimer = setTimeout(function () { stageEl.classList.remove("cue-settling"); cueSettleTimer = null; }, 220);
    }
  }

  function startCueMotion(context) {
    cueMotion = context;
    var velocity = Math.max(-1.4, Math.min(1.4, context.velocity || 0));
    var started = performance.now(), previous = started;
    var target = context.target;
    var rows = context.rows;
    var min = rows[0].top, max = rows[rows.length - 1].top + rows[rows.length - 1].height;
    function frame(now) {
      if (cueMotion !== context) return;
      var dt = Math.min(40, now - previous);
      previous = now;
      var proposed = target - velocity * dt;
      target = Math.max(min, Math.min(max, proposed));
      cueSeekAtHeight(target);
      velocity *= Math.exp(-dt / 100);
      if (now - started > 320 || Math.abs(velocity) < .06 || target !== proposed) finishCueMotion();
      else cueMotionFrame = requestAnimationFrame(frame);
    }
    cueMotionFrame = requestAnimationFrame(frame);
  }

  stageEl.addEventListener("contextmenu", function (event) { event.preventDefault(); });
  stageEl.addEventListener("touchstart", function (event) {
    // A second finger/new sequence must end the previous gesture, even if its end was lost.
    finishCueTouch(true);
    if (cueSettleTimer !== null) clearTimeout(cueSettleTimer);
    cueSettleTimer = null;
    stageEl.classList.remove("cue-settling");
    if (event.touches.length !== 1) return;
    var touch = event.touches[0];
    var line = event.target.closest ? event.target.closest(".line") : null;
    var eligible = !!line && Number(getComputedStyle(line).opacity) > .05;
    cueTouch = { x: touch.clientX, y: touch.clientY, dx: 0, dy: 0, scrubbing: false, eligible: eligible,
      lastY: touch.clientY, lastTime: performance.now(), velocity: 0 };
    if (!state.lyrics.length || !eligible) return;
    cueHoldTimer = setTimeout(function () {
      beginCueTouch(state.isFullLyricsMode ? Number(line.dataset.index) : clampedActiveIndex(), true);
    }, 450);
  }, { passive: true });

  stageEl.addEventListener("touchmove", function (event) {
    if (!cueTouch) return;
    if (event.touches.length !== 1) { finishCueTouch(true); return; }
    var y = event.touches[0].clientY, now = performance.now();
    var dt = Math.max(1, now - cueTouch.lastTime);
    cueTouch.velocity = (y - cueTouch.lastY) / dt;
    cueTouch.lastY = y; cueTouch.lastTime = now;
    cueTouch.dx = event.touches[0].clientX - cueTouch.x;
    cueTouch.dy = y - cueTouch.y;
    if (cueTouch.eligible && !cueTouch.scrubbing && !state.isFullLyricsMode && Math.abs(cueTouch.dy) > 8 && Math.abs(cueTouch.dy) > Math.abs(cueTouch.dx) * 1.25) beginCueTouch(clampedActiveIndex(), false);
    if (cueTouch.scrubbing) {
      event.preventDefault();
      // Coalesce high-frequency touch events into a single paint per display frame.
      if (cueDragFrame === null) cueDragFrame = requestAnimationFrame(function () {
        cueDragFrame = null;
        paintCueTouch();
      });
    } else {
      if (Math.abs(cueTouch.dx) > 10 || Math.abs(cueTouch.dy) > 10) clearCueHold();
      if (Math.abs(cueTouch.dx) > 16 && Math.abs(cueTouch.dx) > Math.abs(cueTouch.dy) * 1.5) event.preventDefault();
    }
  }, { passive: false });

  function paintCueTouch() {
    if (!cueTouch || !cueTouch.scrubbing) return;
    var distance = cueTouch.dy * (cueTouch.fine && !state.isFullLyricsMode ? .35 : 1);
    if (state.isFullLyricsMode) getScrollContainer().scrollTop = cueTouch.scrollTop - distance;
    cueSeekAtHeight(cueTouch.anchorOffset - distance);
  }

  function finishCueTouch(cancelled) {
    clearCueHold();
    if (cueDragFrame !== null) {
      cancelAnimationFrame(cueDragFrame);
      cueDragFrame = null;
      if (!cancelled) paintCueTouch();
    }
    finishCueMotion();
    if (!cueTouch) {
      if (cancelled) { stageEl.classList.remove("cue-scrubbing", "cue-dragging"); cueGuide.hidden = true; }
      return;
    }
    var context = cueTouch;
    cueTouch = null;
    if (context.scrubbing) {
      suppressClickUntil = Date.now() + 350;
      if (!cancelled && !context.fine && performance.now() - context.lastTime < 100 && Math.abs(context.velocity) > .08) startCueMotion(context);
      else completeCueGesture();
    } else if (!cancelled && Math.abs(context.dx) > 70 && Math.abs(context.dx) > Math.abs(context.dy) * 1.5) {
      suppressClickUntil = Date.now() + 350;
      cueBridge(context.dx < 0 ? "openLibrary" : "openSearch");
    } else if (Math.abs(context.dx) > 10 || Math.abs(context.dy) > 10) suppressClickUntil = Date.now() + 350;
  }
  stageEl.addEventListener("touchend", function () { finishCueTouch(false); }, { passive: true });
  stageEl.addEventListener("touchcancel", function () { finishCueTouch(true); }, { passive: true });
  window.addEventListener("blur", function () { finishCueTouch(true); });
  document.addEventListener("visibilitychange", function () { if (document.hidden) finishCueTouch(true); });

  stageEl.addEventListener("click", function (event) {
    if (Date.now() < suppressClickUntil || !state.lyrics.length) return;
    var line = event.target.closest ? event.target.closest(".line") : null;
    if (state.isFullLyricsMode && line) {
      userScrolling = true;
      cueBridge("beginScrub");
      cueSeek(Number(state.lyrics[Number(line.dataset.index)].startTimeMs), Number(line.dataset.index), 0);
      cueBridge("endScrub");
      onUserScrollInteraction();
    } else if (!line) toggleFullLyricsMode();
  });

  // Re-calculate layout and scroll offsets on window resize (rotation / unfolding)
  window.addEventListener("resize", function () {
    finishCueTouch(true);
    try {
      if (state.lyrics.length > 0) {
        if (!state.isFullLyricsMode) {
          // In focused mode, do a full re-render so line visibility/sizing is correct
          prevActiveIndex = -1;
          lyricsEl.style.transition = "none";
          renderFull();
          requestAnimationFrame(function () {
            requestAnimationFrame(function () {
              lyricsEl.style.transition = "";
            });
          });
        } else {
          // In full-lyrics-mode, just re-scroll to active line
          var active = clampedActiveIndex();
          var lines = lyricsEl.querySelectorAll(".line");
          if (lines[active]) {
            scrollToActiveIfNeeded(lines[active]);
          }
        }
      }
      report("resize event handled");
    } catch (error) {
      report("error:窗口尺寸变化处理失败:" + error.message);
    }
  });

  function toggleFullLyricsMode() {
    finishCueTouch(true);
    state.isFullLyricsMode = !state.isFullLyricsMode;
    stageEl.scrollTop = 0;
    getViewport().scrollTop = 0;
    prevActiveIndex = -1;
    if (state.isFullLyricsMode) {
      userScrolling = false; // Reset scroll-pause state on enter
      stageEl.classList.add("full-lyrics-mode");
      // Reset scroll before rendering to avoid starting from previous position
      getScrollContainer().scrollTop = 0;
      renderFull();
      // Instantly jump to active line position (no smooth scroll from top)
      var entryGeneration = layoutGeneration;
      requestAnimationFrame(function () {
        if (!state.isFullLyricsMode || entryGeneration !== layoutGeneration) return;
        var lines = lyricsEl.querySelectorAll(".line");
        if (lines[state.activeIndex]) {
          getScrollContainer().scrollTop = lines[state.activeIndex].offsetTop - window.innerHeight * 0.05;
        }
      });
    } else {
      userScrolling = false;
      if (scrollResumeTimer !== null) {
        clearTimeout(scrollResumeTimer);
        scrollResumeTimer = null;
      }
      stageEl.classList.remove("full-lyrics-mode");
      getScrollContainer().scrollTop = 0;
      // Disable transition to prevent upward bounce animation
      lyricsEl.style.transition = "none";
      renderFull();
      // Re-enable transition after position is painted
      requestAnimationFrame(function () {
        requestAnimationFrame(function () {
          lyricsEl.style.transition = "";
        });
      });
    }
    report("toggleFullLyricsMode: " + state.isFullLyricsMode);
    try {
      if (window.AndroidLyrics && window.AndroidLyrics.setFullLyricsMode) {
        window.AndroidLyrics.setFullLyricsMode(state.isFullLyricsMode);
      }
    } catch (ignore) { }
  }

  report("renderer ready");
})();
