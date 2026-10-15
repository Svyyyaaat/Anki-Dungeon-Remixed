/*
 * Pixel Dungeon
 * Copyright (C) 2012-2015 Oleg Dolya
 *
 * Shattered Pixel Dungeon
 * Copyright (C) 2014-2026 Evan Debenham
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

package com.shatteredpixel.shatteredpixeldungeon.sprites;

import com.shatteredpixel.shatteredpixeldungeon.Assets;
import com.shatteredpixel.shatteredpixeldungeon.Dungeon;
import com.shatteredpixel.shatteredpixeldungeon.actors.buffs.HeroDisguise;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.Hero;
import com.shatteredpixel.shatteredpixeldungeon.actors.hero.HeroClass;
import com.shatteredpixel.shatteredpixeldungeon.scenes.GameScene;
import com.watabou.gltextures.SmartTexture;
import com.watabou.gltextures.TextureCache;
import com.cramdungeon.Kitsune;
import com.watabou.noosa.Camera;
import com.watabou.noosa.Game;
import com.watabou.noosa.Image;
import com.watabou.noosa.TextureFilm;
import com.watabou.utils.Callback;
import com.watabou.utils.PointF;
import com.watabou.utils.RectF;

public class HeroSprite extends CharSprite {
	
	private static final int FRAME_WIDTH	= 12;
	private static final int FRAME_HEIGHT	= 15;
	
	private static final int RUN_FRAMERATE	= 20;
	
	private static TextureFilm tiers;
	
	private Animation fly;
	private Animation read;

	//Cram Dungeon: the Kitsune's tails, drawn behind her
	private Image tails;

	public HeroSprite() {
		super();
		
		texture( spritesheet( Dungeon.hero ) );
		if (Kitsune.is( Dungeon.hero )) {
			tails = new Image( Kitsune.TAILS );
		}
		updateArmor();
		
		link( Dungeon.hero );

		if (ch.isAlive())
			idle();
		else
			die();
	}

	public void disguise(HeroClass cls){
		texture( cls.spritesheet() );
		updateArmor();
	}
	
	public void updateArmor() {

		TextureFilm film = new TextureFilm( tiers(), Dungeon.hero.tier(), FRAME_WIDTH, FRAME_HEIGHT );
		
		idle = new Animation( 1, true );
		idle.frames( film, 0, 0, 0, 1, 0, 0, 1, 1 );
		
		run = new Animation( RUN_FRAMERATE, true );
		run.frames( film, 2, 3, 4, 5, 6, 7 );
		
		die = new Animation( 20, false );
		die.frames( film, 8, 9, 10, 11, 12, 11 );
		
		attack = new Animation( 15, false );
		attack.frames( film, 13, 14, 15, 0 );
		
		zap = attack.clone();
		
		operate = new Animation( 8, false );
		operate.frames( film, 16, 17, 16, 17 );
		
		fly = new Animation( 1, true );
		fly.frames( film, 18 );

		read = new Animation( 20, false );
		read.frames( film, 19, 20, 20, 20, 20, 20, 20, 20, 20, 19 );
		
		if (Dungeon.hero.isAlive())
			idle();
		else
			die();
	}
	
	@Override
	public void place( int p ) {
		super.place( p );
		if (Game.scene() instanceof GameScene) Camera.main.panFollow(this, 5f);
	}

	@Override
	public void move( int from, int to ) {
		super.move( from, to );
		if (ch != null && ch.flying) {
			play( fly );
		}
		Camera.main.panFollow(this, 20f);
	}

	@Override
	public void idle() {
		super.idle();
		if (ch != null && ch.flying) {
			play( fly );
		}
	}

	@Override
	public void jump( int from, int to, float height, float duration,  Callback callback ) {
		super.jump( from, to, height, duration, callback );
		play( fly );
		Camera.main.panFollow(this, 20f);
	}

	public synchronized void read() {
		animCallback = new Callback() {
			@Override
			public void call() {
				idle();
				ch.onOperateComplete();
			}
		};
		play( read );
	}

	@Override
	public void bloodBurstA(PointF from, int damage) {
		//Does nothing.

		/*
		 * This is both for visual clarity, and also for content ratings regarding violence
		 * towards human characters. The heroes are the only human or human-like characters which
		 * participate in combat, so removing all blood associated with them is a simple way to
		 * reduce the violence rating of the game.
		 */
	}

	@Override
	public void update() {
		sleeping = ch.isAlive() && ((Hero)ch).resting;
		
		super.update();
	}
	
	private static Object spritesheet( Hero hero ) {
		return Kitsune.is( hero ) ? Kitsune.SPRITES : hero.heroClass.spritesheet();
	}

	@Override
	public void draw() {
		if (tails != null && ch instanceof Hero && ((Hero) ch).tails > 0) {
			//her shadow, then the tails, then her: the shadow must not land on the tails
			drawShadow();
			drawTails( ((Hero) ch).tails );
			drawWithoutShadow();
		} else {
			super.draw();
		}
	}

	private void drawTails( int count ) {
		if (frame == null) return;
		int index = Math.round( frame.left * texture.width / FRAME_WIDTH );
		int[] off = Kitsune.frameOffset( index );
		if (off == null) return;

		int n = Math.min( count, Kitsune.MAX_TAILS );
		tails.frame( tails.texture.uvRect( (n - 1) * Kitsune.TAIL_W, 0, n * Kitsune.TAIL_W, Kitsune.TAIL_H ) );
		tails.scale.set( scale );
		tails.flipHorizontal = flipHorizontal;
		if (flipHorizontal) {
			tails.x = x - (Kitsune.TAIL_W - Kitsune.HERO_X - FRAME_WIDTH + off[0]) * scale.x;
		} else {
			tails.x = x - (Kitsune.HERO_X - off[0]) * scale.x;
		}
		tails.y = y - (Kitsune.HERO_Y - off[1]) * scale.y;
		tails.rm = rm; tails.gm = gm; tails.bm = bm; tails.am = am;
		tails.ra = ra; tails.ga = ga; tails.ba = ba; tails.aa = aa;
		tails.camera = camera();
		tails.draw();
	}

	public void sprint( float speed ) {
		run.delay = 1f / speed / RUN_FRAMERATE;
	}
	
	public static TextureFilm tiers() {
		if (tiers == null) {
			SmartTexture texture = TextureCache.get( Assets.Sprites.ROGUE );
			tiers = new TextureFilm( texture, texture.width, FRAME_HEIGHT );
		}
		
		return tiers;
	}

	public static Image avatar( Hero hero ){
		if (Kitsune.is( hero ) && hero.buff(HeroDisguise.class) == null){
			RectF patch = tiers().get( hero.tier() );
			Image avatar = new Image( Kitsune.SPRITES );
			RectF frame = avatar.texture.uvRect( 1, 0, FRAME_WIDTH, FRAME_HEIGHT );
			frame.shift( patch.left, patch.top );
			avatar.frame( frame );
			return avatar;
		}
		if (hero.buff(HeroDisguise.class) != null){
			return avatar(hero.buff(HeroDisguise.class).getDisguise(), hero.tier());
		} else {
			return avatar(hero.heroClass, hero.tier());
		}
	}
	
	public static Image avatar( HeroClass cl, int armorTier ) {
		
		RectF patch = tiers().get( armorTier );
		Image avatar = new Image( cl.spritesheet() );
		RectF frame = avatar.texture.uvRect( 1, 0, FRAME_WIDTH, FRAME_HEIGHT );
		frame.shift( patch.left, patch.top );
		avatar.frame( frame );
		
		return avatar;
	}
}
