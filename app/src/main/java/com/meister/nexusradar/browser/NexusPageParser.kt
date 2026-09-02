package com.meister.nexusradar.browser

/**
 * JavaScript is evaluated only inside the Nexus page opened in the embedded WebView.
 * It reads visible mod metadata, but never passwords, form values or cookies.
 */
object NexusPageParser {
    val parseCurrentMod: String = """
        (function() {
          const result = {
            kind: 'mod', mod_id: 0, name: '', author: null, version: null,
            category: null, summary: null, published_at: null, updated_at: null,
            adult: false, url: location.href, tags: [], requirements: [], required_by: [],
            content_type: 'MOD', diagnostics: []
          };

          const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
          const text = (element) => element ? clean(element.textContent) : '';
          const uniq = (values) => [...new Set(values.map(clean).filter(Boolean))];
          const hrefId = (href) => {
            const match = String(href || '').match(/\/skyrimspecialedition\/mods\/(\d+)/i);
            return match ? Number(match[1]) : 0;
          };
          const metaProperty = (name) => document.querySelector('meta[property="' + name + '"]')?.content || null;
          const metaName = (name) => document.querySelector('meta[name="' + name + '"]')?.content || null;

          result.mod_id = hrefId(location.href);
          result.name = text(document.querySelector('main h1, h1')) ||
                        clean(metaProperty('og:title')) ||
                        clean(document.title.replace(/ at Skyrim Special Edition Nexus.*$/i, ''));
          result.summary = metaName('description') || metaProperty('og:description') || null;

          const structured = [];
          for (const script of document.querySelectorAll('script[type="application/ld+json"]')) {
            try {
              const parsed = JSON.parse(script.textContent || 'null');
              if (Array.isArray(parsed)) structured.push(...parsed);
              else if (parsed) structured.push(parsed);
            } catch (_) {}
          }
          const deepValue = (keys) => {
            const wanted = new Set(keys.map(x => x.toLowerCase()));
            const stack = [...structured];
            let visited = 0;
            while (stack.length && visited < 1000) {
              const current = stack.shift();
              visited++;
              if (!current || typeof current !== 'object') continue;
              for (const [key, value] of Object.entries(current)) {
                if (wanted.has(key.toLowerCase()) && value != null) {
                  if (typeof value === 'object' && value.name) return value.name;
                  if (typeof value !== 'object') return value;
                }
                if (value && typeof value === 'object') {
                  if (Array.isArray(value)) stack.push(...value);
                  else stack.push(value);
                }
              }
            }
            return null;
          };

          const authorLink = [...document.querySelectorAll('a[href*="/users/"]')]
            .find(a => text(a).length > 1 && !/profile|uploaded by/i.test(text(a)));
          result.author = clean(deepValue(['author', 'creator'])) || text(authorLink) || null;

          const categoryLinks = [...document.querySelectorAll('a[href*="categoryName="]')];
          const categoryLink = categoryLinks.find(a => {
            const label = text(a);
            return label && !/remove filter|clear|mod categories/i.test(label);
          });
          if (categoryLink) {
            result.category = text(categoryLink);
            if (!result.category) {
              try { result.category = clean(new URL(categoryLink.href, location.href).searchParams.get('categoryName')); } catch (_) {}
            }
          }
          if (!result.category) result.category = clean(deepValue(['categoryName', 'category'])) || null;

          const tagHeading = [...document.querySelectorAll('h1,h2,h3,h4,h5')]
            .find(h => /^Tags for this mod$/i.test(text(h)));
          if (tagHeading) {
            let tagBox = tagHeading.parentElement;
            for (let i = 0; tagBox && i < 3; i++, tagBox = tagBox.parentElement) {
              const labels = [...tagBox.querySelectorAll('a')]
                .map(text)
                .filter(label => label && !/view more/i.test(label));
              if (labels.length) { result.tags = uniq(labels); break; }
            }
          }
          if (!result.tags.length) {
            result.tags = uniq([...document.querySelectorAll('a[href*="tag="][href*="skyrimspecialedition"], a[href*="tags="][href*="skyrimspecialedition"]')].map(text));
          }

          const body = document.body?.innerText || '';
          const leafLabels = (pattern) => [...document.querySelectorAll('h1,h2,h3,h4,h5,dt,th,strong,span,p,div')]
            .filter(element => element.children.length === 0 && pattern.test(text(element)));
          const valueAfterLabel = (pattern) => {
            for (const label of leafLabels(pattern)) {
              const sibling = label.nextElementSibling;
              if (sibling && text(sibling) && !pattern.test(text(sibling))) {
                const time = sibling.matches('time') ? sibling : sibling.querySelector('time');
                return time?.getAttribute('datetime') || text(sibling);
              }
              const parent = label.parentElement;
              if (!parent) continue;
              const time = parent.querySelector('time');
              if (time) return time.getAttribute('datetime') || text(time);
              const values = [...parent.children].map(text).filter(value => value && !pattern.test(value));
              if (values.length) return values[0];
            }
            return null;
          };
          const normalizeDate = (value) => {
            if (!value) return null;
            const cleaned = clean(value);
            const parsed = new Date(cleaned);
            return isNaN(parsed.getTime()) ? cleaned : parsed.toISOString();
          };

          result.version = clean(
            deepValue(['version', 'softwareVersion', 'currentVersion']) ||
            valueAfterLabel(/^(Version|Current version)$/i)
          ) || null;
          result.updated_at = normalizeDate(
            deepValue(['dateModified', 'updatedAt', 'updated_at', 'lastUpdated']) ||
            valueAfterLabel(/^(Last updated|Updated)$/i)
          );
          result.published_at = normalizeDate(
            deepValue(['datePublished', 'createdAt', 'publishedAt', 'published_at']) ||
            valueAfterLabel(/^(Original upload|Uploaded|Published|Created)$/i)
          );

          const scriptsText = [...document.scripts].map(s => s.textContent || '').join('\n');
          const embeddedValue = (names) => {
            const expression = new RegExp('"(?:' + names.join('|') + ')"\\s*:\\s*"([^"\\\\]+)"', 'i');
            const match = scriptsText.match(expression);
            return match ? match[1] : null;
          };
          if (!result.version) result.version = clean(embeddedValue(['version', 'currentVersion'])) || null;
          if (!result.updated_at) result.updated_at = normalizeDate(embeddedValue(['dateModified', 'updatedAt', 'updated_at', 'lastUpdated']));
          if (!result.published_at) result.published_at = normalizeDate(embeddedValue(['datePublished', 'createdAt', 'publishedAt', 'published_at']));

          if (!result.version) {
            const match = body.match(/(?:^|\n)Version\s*\n+\s*([^\n]{1,40})/i);
            if (match) result.version = clean(match[1]);
          }
          if (!result.updated_at) {
            const match = body.match(/(?:^|\n)Last updated\s*\n+\s*([^\n]{3,80})/i);
            if (match) result.updated_at = normalizeDate(match[1]);
          }
          if (!result.published_at) {
            const match = body.match(/(?:^|\n)Original upload\s*\n+\s*([^\n]{3,80})/i);
            if (match) result.published_at = normalizeDate(match[1]);
          }

          const adultMarker = document.querySelector(
            '[data-adult-content="true"], [data-testid*="adult-warning" i], [class*="adult-warning" i]'
          );
          result.adult = adultMarker !== null ||
            /this mod contains adult content|adult-only content|adult content warning/i.test(body);

          const collectFollowingLinks = (headingPattern) => {
            const output = [];
            const headings = [...document.querySelectorAll('h1,h2,h3,h4,h5,strong')]
              .filter(h => headingPattern.test(text(h)));
            for (const heading of headings) {
              let node = heading.nextElementSibling;
              let walked = 0;
              while (node && walked < 10) {
                if (/^H[1-5]$/.test(node.tagName)) break;
                for (const anchor of node.querySelectorAll('a[href*="/skyrimspecialedition/mods/"]')) {
                  const id = hrefId(anchor.href);
                  const name = text(anchor);
                  if (id && id !== result.mod_id && name) output.push({mod_id:id, name:name, url:anchor.href});
                }
                node = node.nextElementSibling;
                walked++;
              }
              if (!output.length) {
                const parent = heading.parentElement;
                for (const anchor of parent?.querySelectorAll('a[href*="/skyrimspecialedition/mods/"]') || []) {
                  const id = hrefId(anchor.href);
                  const name = text(anchor);
                  if (id && id !== result.mod_id && name) output.push({mod_id:id, name:name, url:anchor.href});
                }
              }
            }
            const seen = new Set();
            return output.filter(item => !seen.has(item.mod_id) && seen.add(item.mod_id));
          };
          result.requirements = collectFollowingLinks(/^Nexus requirements$/i);
          result.required_by = collectFollowingLinks(/^Mods (requiring|using) this (file|mod)(?:\s*\(\d+\))?$/i);

          if (!result.mod_id) result.diagnostics.push('mod_id_missing');
          if (!result.name) result.diagnostics.push('name_missing');
          if (!result.version) result.diagnostics.push('version_missing');
          if (!result.category) result.diagnostics.push('category_missing');
          if (!result.published_at) result.diagnostics.push('published_at_missing');
          if (!result.updated_at) result.diagnostics.push('updated_at_missing');
          return JSON.stringify(result);
        })();
    """.trimIndent()

    val collectVisibleModLinks: String = """
        (function() {
          const seen = new Set();
          const items = [];
          for (const anchor of document.querySelectorAll('a[href*="/skyrimspecialedition/mods/"]')) {
            const match = anchor.href.match(/\/skyrimspecialedition\/mods\/(\d+)/i);
            if (!match) continue;
            const id = Number(match[1]);
            if (!id || seen.has(id)) continue;
            seen.add(id);
            items.push({
              mod_id: id,
              url: anchor.href.split('?')[0].split('#')[0],
              name: (anchor.textContent || '').replace(/\s+/g, ' ').trim()
            });
          }
          const next = document.querySelector('a[rel="next"]') ||
            [...document.querySelectorAll('a')].find(anchor =>
              /^(next|weiter|›|→)$/i.test((anchor.textContent || '').trim()) && /page=/i.test(anchor.href || '')
            );
          return JSON.stringify({kind:'links', url:location.href, next_url:next ? next.href : null, links:items});
        })();
    """.trimIndent()
}
