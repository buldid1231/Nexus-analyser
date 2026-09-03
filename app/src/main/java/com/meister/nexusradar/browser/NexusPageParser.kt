package com.meister.nexusradar.browser

/**
 * JavaScript is evaluated only inside the Nexus page opened in the embedded WebView.
 * It reads visible mod metadata, but never passwords, form values or cookies.
 */
object NexusPageParser {
    val expandMetadataSections: String = """
        (function() {
          const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
          const wanted = /^(Nexus requirements|Mods (requiring|using) this (file|mod))(?:\s*\(\d+\))?$/i;
          let clicked = 0;
          for (const element of document.querySelectorAll('button, summary, [role="button"], h2, h3, h4')) {
            if (!wanted.test(clean(element.textContent))) continue;
            const target = element.matches('button, summary, [role="button"]')
              ? element
              : element.closest('button, summary, [role="button"]');
            if (target && target.getAttribute('aria-expanded') !== 'true' && !target.open) {
              try { target.click(); clicked++; } catch (_) {}
            }
          }
          return clicked;
        })();
    """.trimIndent()

    val parseCurrentMod: String = """
        (function() {
          const result = {
            kind: 'mod', mod_id: 0, name: '', author: null, version: null,
            category: null, summary: null, published_at: null, updated_at: null,
            adult: false, url: location.href.split('?')[0].split('#')[0],
            file_size_bytes: null, main_files_count: 0,
            endorsements: null, unique_downloads: null, total_downloads: null,
            tags: [], requirements: [], required_by: [],
            requirements_count: 0, required_by_count: 0,
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
              const values = [...parent.children]
                .filter(child => child !== label)
                .map(text)
                .filter(value => value && !pattern.test(value));
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
          const parseCount = (value) => {
            if (value == null) return null;
            const normalized = clean(value).replace(/\s/g, '').replace(/,/g, '');
            const match = normalized.match(/([0-9]+(?:\.[0-9]+)?)\s*([KMB])?/i);
            if (!match) return null;
            const multiplier = ({K:1000, M:1000000, B:1000000000})[(match[2] || '').toUpperCase()] || 1;
            return Math.round(Number(match[1]) * multiplier);
          };

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
            const wanted = new Set(keys.map(key => key.toLowerCase()));
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

          const labelledAuthor = valueAfterLabel(/^(Created by|Uploaded by)$/i);
          const structuredAuthor = deepValue(['author', 'creator']);
          const cleanAuthor = clean(labelledAuthor || structuredAuthor);
          result.author = cleanAuthor && !/^(My mods|My profile|Guest)$/i.test(cleanAuthor)
            ? cleanAuthor
            : null;

          const categoryLinks = [...document.querySelectorAll('a[href*="categoryName="]')];
          const categoryLink = categoryLinks.find(anchor => {
            const label = text(anchor);
            return label && !/remove filter|clear|mod categories/i.test(label);
          });
          if (categoryLink) {
            result.category = text(categoryLink);
            if (!result.category) {
              try {
                result.category = clean(new URL(categoryLink.href, location.href).searchParams.get('categoryName'));
              } catch (_) {}
            }
          }
          if (!result.category) result.category = clean(deepValue(['categoryName', 'category'])) || null;

          const tagHeading = [...document.querySelectorAll('h1,h2,h3,h4,h5,strong')]
            .find(heading => /^Tags for this mod$/i.test(text(heading)));
          if (tagHeading) {
            const candidates = [];
            let node = tagHeading.nextElementSibling;
            let walked = 0;
            while (node && walked < 6 && !/^H[1-5]$/.test(node.tagName)) {
              for (const anchor of node.querySelectorAll('a')) {
                const href = anchor.getAttribute('href') || '';
                if (/tag|search/i.test(href)) candidates.push(text(anchor));
              }
              node = node.nextElementSibling;
              walked++;
            }
            if (!candidates.length) {
              const box = tagHeading.closest('section, article') || tagHeading.parentElement;
              for (const anchor of box?.querySelectorAll('a') || []) {
                const href = anchor.getAttribute('href') || '';
                if (/tag|search/i.test(href)) candidates.push(text(anchor));
              }
            }
            result.tags = uniq(candidates).filter(label =>
              label.length <= 80 && !/^(View more|Tag this mod|Manage tags)$/i.test(label)
            );
          }
          if (!result.tags.length) {
            result.tags = uniq([...document.querySelectorAll(
              'a[href*="tag="][href*="skyrimspecialedition"], a[href*="tags="][href*="skyrimspecialedition"]'
            )].map(text)).filter(label => !/^(View more|Tag this mod|Manage tags)$/i.test(label));
          }

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
          result.endorsements = parseCount(valueAfterLabel(/^Endorsements?$/i));
          result.unique_downloads = parseCount(valueAfterLabel(/^Unique DLs?$/i));
          result.total_downloads = parseCount(valueAfterLabel(/^Total DLs?$/i));

          const scriptsText = [...document.scripts].map(script => script.textContent || '').join('\n');
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
            const headings = [...document.querySelectorAll('h1,h2,h3,h4,h5,strong,button,summary')]
              .filter(heading => headingPattern.test(text(heading)));
            for (const heading of headings) {
              let node = heading.nextElementSibling;
              let walked = 0;
              while (node && walked < 12) {
                if (/^H[1-5]$/.test(node.tagName)) break;
                for (const anchor of node.querySelectorAll('a[href*="/skyrimspecialedition/mods/"]')) {
                  const id = hrefId(anchor.href);
                  const name = text(anchor);
                  if (id && id !== result.mod_id && name) {
                    output.push({mod_id:id, name:name, url:anchor.href.split('?')[0].split('#')[0]});
                  }
                }
                node = node.nextElementSibling;
                walked++;
              }
              if (!output.length) {
                const parent = heading.closest('section, article, details') || heading.parentElement;
                for (const anchor of parent?.querySelectorAll('a[href*="/skyrimspecialedition/mods/"]') || []) {
                  const id = hrefId(anchor.href);
                  const name = text(anchor);
                  if (id && id !== result.mod_id && name) {
                    output.push({mod_id:id, name:name, url:anchor.href.split('?')[0].split('#')[0]});
                  }
                }
              }
            }
            const seen = new Set();
            return output.filter(item => !seen.has(item.mod_id) && seen.add(item.mod_id));
          };
          result.requirements = collectFollowingLinks(/^Nexus requirements$/i);
          result.required_by = collectFollowingLinks(/^Mods (requiring|using) this (file|mod)(?:\s*\(\d+\))?$/i);
          result.requirements_count = result.requirements.length;
          result.required_by_count = result.required_by.length;
          const requiredByHeading = [...document.querySelectorAll('h1,h2,h3,h4,h5,strong,button,summary')]
            .map(text)
            .find(label => /^Mods (requiring|using) this (file|mod)(?:\s*\(\d+\))?$/i.test(label));
          const requiredByMatch = String(requiredByHeading || '').match(/\((\d[\d,]*)\)/);
          if (requiredByMatch) {
            result.required_by_count = Math.max(
              result.required_by_count,
              Number(requiredByMatch[1].replace(/,/g, ''))
            );
          }
          const requiredByBodyMatch = body.match(
            /Mods (?:requiring|using) this (?:file|mod)\s*\(([\d,]+)\)/i
          );
          if (requiredByBodyMatch) {
            result.required_by_count = Math.max(
              result.required_by_count,
              Number(requiredByBodyMatch[1].replace(/,/g, ''))
            );
          }

          if (!result.mod_id) result.diagnostics.push('mod_id_missing');
          if (!result.name) result.diagnostics.push('name_missing');
          if (!result.author) result.diagnostics.push('author_missing');
          if (!result.version) result.diagnostics.push('version_missing');
          if (!result.category) result.diagnostics.push('category_missing');
          if (!result.published_at) result.diagnostics.push('published_at_missing');
          if (!result.updated_at) result.diagnostics.push('updated_at_missing');
          return JSON.stringify(result);
        })();
    """.trimIndent()

    val parseFilesTab: String = """
        (function() {
          const clean = (value) => String(value || '').replace(/\s+/g, ' ').trim();
          const toBytes = (value) => {
            const normalized = clean(value).replace(',', '.');
            const match = normalized.match(/([0-9]+(?:\.[0-9]+)?)\s*(B|KB|MB|GB|TB|BYTE|BYTES)\b/i);
            if (!match) return null;
            const powers = {B:0, BYTE:0, BYTES:0, KB:1, MB:2, GB:3, TB:4};
            return Math.round(Number(match[1]) * Math.pow(1024, powers[match[2].toUpperCase()] || 0));
          };
          const body = document.body?.innerText || '';
          const start = body.search(/(?:^|\n)Main files\s*(?:\n|$)/i);
          let segment = start >= 0 ? body.slice(start) : body;
          const end = segment.slice(20).search(/(?:^|\n)(Updates|Optional files|Miscellaneous files|Old files|Archived files)\s*(?:\n|$)/i);
          if (end >= 0) segment = segment.slice(0, end + 20);

          const labels = [];
          const expression = /File size\s*(?:\n|:)\s*([0-9]+(?:[.,][0-9]+)?\s*(?:B|KB|MB|GB|TB|Bytes?))/gi;
          let match;
          while ((match = expression.exec(segment)) !== null) labels.push(clean(match[1]));

          if (!labels.length) {
            for (const label of document.querySelectorAll('dt, th, strong, span, div')) {
              if (label.children.length || !/^File size$/i.test(clean(label.textContent))) continue;
              const sibling = label.nextElementSibling;
              if (sibling) labels.push(clean(sibling.textContent));
            }
          }

          const uniqueLabels = [...new Set(labels)];
          const sizes = uniqueLabels.map(toBytes).filter(value => value != null && value >= 0);
          return JSON.stringify({
            file_size_bytes: sizes.length ? Math.max(...sizes) : null,
            main_files_count: sizes.length
          });
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
